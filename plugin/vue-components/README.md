# Vue 组件（插件 `vue-components`）

Vue 3 单文件组件 = **wiki 页面**（`*.vue`），同名解析标签直接嵌进正文。
浏览器端编译（本地打包的 `vue.esm-browser.prod.js`，不依赖 CDN / 构建链）。

## 写一个组件

页面标题 `组件:Test-component.vue`（或任意命名空间的 `test-component.vue`；标签名 = 文件名去 `.vue` 转 kebab-case，大小写不敏感）：

```vue
<template>
  <div class="test-card">
    <h3>{{ title }}（{{ count }}）</h3>
    <p>插槽：<slot /></p>
    <button type="button" @click="n++">点了 {{ n }} 次</button>
  </div>
</template>

<script>
export default {
  props: {
    title: { type: String, default: '默认标题' },
    count: { type: Number, default: 0 }
  },
  data() { return { n: this.count }; }
};
</script>

<style>
.test-card { border: 1px solid #e0c8d6; border-radius: 10px; padding: 12px 16px; background: #fff8fb; }
</style>
```

## 在页面里用

```wikitext
<test-component title="你好世界" :count="3" />

<test-component title="带插槽">
  槽里可以写 wikitext，也可以再放组件：<test-badge text="子组件" />
</test-component>
```

- `<test-component />`（自闭合）与 `<test-component>…</test-component>`（成对）都可以；**标签内容 = 默认插槽**；
- **可以嵌套**（插槽里的组件会作为真正的子组件渲染，父子都能响应式更新）；
- 属性 → props：
  - 普通属性按字符串传（值会先按上下文展开，支持 `{{PAGENAME}}`、`[[链接]]` 等）；
  - `:` 前缀的按 **严格 JSON** 解析：`:count="3"`（数字）、`:items='["甲","乙"]'`（数组，HTML 属性用单引号、JSON 用双引号）、`:open="true"`；写不成 JSON 时按字符串处理并在控制台提示（**不做 JS 求值**：页面任何人都能编辑，禁止借属性执行代码）；
- 组件页面本身是**代码页**（HTML 高亮编辑、`?action=raw` 取源码）。

## 嵌套与写法约定（v1）

- `<script>` 里用 `export default defineComponent({...})`（options 或 setup 函数都可以）；也支持 `<script setup>`：
  ```vue
  <script setup>
  import { ref, computed } from 'vue';

  const props = defineProps({ title: String });
  const emit = defineEmits(['change']);
  const count = ref(0);
  const doubled = computed(() => count.value * 2);
  </script>
  ```
  浏览器端轻量编译器会把顶层变量 / 函数 / import 暴露给模板，并支持
  `defineProps`（运行时对象 / 数组）、`defineEmits`、`withDefaults`、`defineOptions`、
  `defineExpose`、`defineSlots`、`defineModel` 的常用写法；
  **不支持** `<script setup lang="ts">`（只支持 JavaScript）、TypeScript 类型宏、顶层 `await`。
- `<style>` 注入全局（暂不支持 `<style scoped>`），建议类名前缀（如 `test-`）避免冲突；
- `<script>` 里 `import` 请用**绝对路径**或 `mw.wikiImport('组件:工具.ts')`（Blob 模块没有相对基址）；
  从 `'vue'` 导入会自动指向插件自带的 Vue ESM 文件；
- 组件脚本由**界面管理员**维护，等价于交互式脚本权限。

## 权限与开关

- `*.vue` 与 `.js` / `.css` 一样属于**界面页**：默认只有 **界面管理员** 能编辑 / 创建 / 移动；
  可在「特殊:权限配置」按页面或通配加精确规则（例如把某个组件开放给「小部件编辑者」）；
- 功能开关 `features.vue-components`（默认开）：安装向导 `/init` 与「特殊:权限配置 → 插件设置」里都能改；
- 只有**用到组件**的页面才注入加载器（页面属性 `vue-components-used`）。

## 实现结构

| 文件 | 作用 |
| --- | --- |
| `hook/VueComponentTag.java` | 解析标签：一个实例承载所有组件名；属性 → props；插槽内嵌套组件递归转换 |
| `service/ComponentRegistry.java` | 组件注册表：`组件:` 命名空间前缀查询 + 全站 `*.vue` 扫描（缓存 15s，保存即失效） |
| `hook/VueHeadProvider.java` | 页面级注入：用到组件才加载 `vue-components.js` / `.css` |
| `hook/VueEditGuard.java`（内部类） | 页面保存后让注册表缓存失效（新建组件立即可用） |
| `static/plugins/vue-components/vue-components.js` | 浏览器端加载器：拉源码、拆 SFC、编译模板、注入样式、递归挂载 |
| `static/plugins/vue-components/vue.esm-browser.prod.js` | 本地打包的 Vue 3.5（含运行时编译器） |

组件不存在 / 加载失败会在原位显示一行 `wiki-error`，并在浏览器控制台打日志。
