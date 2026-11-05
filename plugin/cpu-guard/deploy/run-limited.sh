#!/usr/bin/env bash
# Debian + systemd + cgroup v2. Run in the wiki deployment directory.
set -euo pipefail
fail() { printf '[CPU Guard] %s\n' "$*" >&2; exit 1; }
percent=${CPU_GUARD_PERCENT:-25}
basis=${CPU_GUARD_BASIS:-single-core}
cores=${CPU_GUARD_LOCK_CORES:-1}
machine=${CPU_GUARD_MACHINE_CPUS:-2}
java_bin=${JAVA_BIN:-java}
jar=${1:-./wikikernel-server.jar}
if (($#)); then shift; fi
[[ $percent =~ ^[0-9]{1,2}$ ]] && ((10#$percent >= 1 && 10#$percent <= 30)) || fail 'CPU_GUARD_PERCENT must be 1..30'
[[ $basis == machine || $basis == single-core ]] || fail 'CPU_GUARD_BASIS must be machine or single-core'
[[ $cores =~ ^[0-9]{1,4}$ ]] && ((10#$cores >= 1 && 10#$cores <= 4096)) || fail 'CPU_GUARD_LOCK_CORES must be 1..4096'
[[ $machine =~ ^[0-9]{1,4}$ ]] && ((10#$machine <= 4096)) || fail 'CPU_GUARD_MACHINE_CPUS must be 0..4096'
percent=$((10#$percent)); cores=$((10#$cores)); machine=$((10#$machine))
[[ -f /sys/fs/cgroup/cgroup.controllers ]] || fail 'A cgroup v2 system is required'
[[ -f $jar ]] || fail "JAR not found: $jar"
command -v systemd-run >/dev/null || fail 'systemd-run is unavailable'
command -v "$java_bin" >/dev/null || fail 'Java is unavailable; set JAVA_BIN if necessary'
online=$(getconf _NPROCESSORS_ONLN)
[[ $online =~ ^[0-9]+$ ]] && ((online >= 1 && online <= 4096)) || fail 'Cannot determine online vCPU count'
if ((machine == 0)); then machine=$online; fi
((machine <= online)) || fail 'Configured VPS CPU count exceeds the online CPU count'
allowed=$(awk '/^Cpus_allowed_list:/ { print $2 }' /proc/self/status)
[[ -n $allowed ]] || fail 'Cannot determine allowed CPUs'
selected=()
IFS=',' read -ra ranges <<< "$allowed"
for range in "${ranges[@]}"; do
    [[ $range =~ ^([0-9]+)(-([0-9]+))?$ ]] || fail 'Invalid allowed CPU list'
    first=${BASH_REMATCH[1]}; last=${BASH_REMATCH[3]:-$first}
    for ((cpu=first; cpu<=last && ${#selected[@]}<cores; cpu++)); do selected+=("$cpu"); done
    ((${#selected[@]} < cores)) || break
done
((${#selected[@]} == cores)) || fail 'Requested more cores than the process is allowed to use'
cpus=$(IFS=','; printf '%s' "${selected[*]}")
quota=$percent
if [[ $basis == machine ]]; then quota=$((percent * machine)); fi
bus=()
if ((EUID != 0)); then bus=(--user); fi
printf '[CPU Guard] basis=%s, target=%s%%, VPS CPUs=%s, bound CPUs=%s, systemd CPUQuota=%s%%\n' "$basis" "$percent" "$machine" "$cpus" "$quota"
# No eval. If systemd cannot enforce the scope, Java is not started.
exec systemd-run "${bus[@]}" --scope --unit="wikikernel-cpu-guard-$$" \
    --property=CPUAccounting=yes --property="CPUQuota=$quota%" \
    --property=CPUQuotaPeriodSec=100ms --property="AllowedCPUs=$cpus" \
    -- "$java_bin" "-XX:ActiveProcessorCount=$cores" \
    "-Dwikikernel.cpu-guard.percent=$percent" "-Dwikikernel.cpu-guard.basis=$basis" \
    "-Dwikikernel.cpu-guard.locked-cores=$cores" "-Dwikikernel.cpu-guard.machine-cpus=$machine" \
    -Dfile.encoding=UTF-8 -jar "$jar" "$@"
