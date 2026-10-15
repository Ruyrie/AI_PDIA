# 后台网页依赖的第三方库（原样复制的发布文件，不要手改）

| 目录 | 库 | 版本 | 许可证 |
|---|---|---|---|
| vue/ | Vue（vue.global.prod.js，含模板编译器） | 3.5.42 | MIT |
| element-plus/ | Element Plus（index.full.min.js、index.css、locale/zh-cn.min.js） | 2.14.5 | MIT |
| element-plus-icons/ | @element-plus/icons-vue（index.iife.min.js） | 2.3.2 | MIT |
| echarts/ | Apache ECharts（echarts.min.js） | 5.6.0 | Apache-2.0 |

直接放在项目中而不用 WebJars：Maven Central 上 Element Plus 的 WebJar 最新只有 2.1.10，缺少后台用到的组件与属性；
也不用公网 CDN（国内访问不稳定）。升级时用对应 npm 包 dist 目录中的同名文件替换，并更新本表。
