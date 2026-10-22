package cc.thonly.wikikernel.plugin.reverseproxy.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 站点设置里保存的路由配置（JSON），整个配置只有一个键。 */
@Data
@NoArgsConstructor
public class ReverseProxyConfig {

    private List<ReverseProxyRoute> routes = new ArrayList<>();

    public ReverseProxyConfig(List<ReverseProxyRoute> routes) {
        this.routes = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
    }
}
