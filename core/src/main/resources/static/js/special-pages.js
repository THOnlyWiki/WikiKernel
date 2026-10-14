/*
 * 「特殊:特殊页面」：搜索框按关键字过滤分组里的特殊页（没有命中的分组自动隐藏）。
 */
(function () {
    'use strict';

    var input = document.getElementById('specialpages-search');
    if (!input) {
        return;
    }
    var groups = Array.prototype.slice.call(document.querySelectorAll('.specialpages-group'));

    input.addEventListener('input', function () {
        var keyword = input.value.trim().toLowerCase();
        groups.forEach(function (group) {
            var visible = 0;
            Array.prototype.forEach.call(group.querySelectorAll('.specialpages-items li'), function (li) {
                var match = !keyword || li.textContent.toLowerCase().indexOf(keyword) >= 0;
                li.style.display = match ? '' : 'none';
                if (match) {
                    visible++;
                }
            });
            group.style.display = (!keyword || visible > 0) ? '' : 'none';
        });
    });
})();
