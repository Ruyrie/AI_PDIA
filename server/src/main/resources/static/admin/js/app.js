/*
 * 管理后台入口：注册路由与组件，登录守卫，挂载应用。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var computed = Vue.computed;

  var ROUTES = [
    { path: '/login', name: 'login', component: A.pages.Login, bare: true, public: true, title: '登录' },
    { path: '/password-required', name: 'password-required', component: A.pages.PasswordRequired, bare: true, title: '修改初始密码' },
    { path: '/dashboard', name: 'dashboard', component: A.pages.Dashboard, title: '数据统计', menu: '/dashboard' },
    { path: '/users', name: 'users', component: A.pages.Users, title: '用户管理', menu: '/users' },
    { path: '/users/:id', name: 'user-detail', component: A.pages.UserDetail, title: '用户详情', menu: '/users' },
    { path: '/config', name: 'config', component: A.pages.Config, title: '系统配置', menu: '/config' },
    { path: '/logs', name: 'logs', component: A.pages.Logs, title: '操作日志', menu: '/logs' }
  ];
  ROUTES.forEach(function (r) { r.component = Vue.markRaw(r.component); });
  A.router.add(ROUTES);

  var BASE_TITLE = '病虫害识别助手 · 管理后台';
  var store = A.store;
  var bootPromise = null;

  /** 首次进入时取一次当前管理员（未登录返回 40101，不提示） */
  function boot() {
    if (!bootPromise) {
      bootPromise = A.api.refreshMe().catch(function (err) {
        if (err && err.code !== 40101) {
          A.message.error(err.message || '无法连接服务器');
        }
        return null;
      }).then(function () {
        store.ready = true;
      });
    }
    return bootPromise;
  }

  /** 路由守卫：返回需要跳转的路径，或 null 放行 */
  function guard(to) {
    return boot().then(function () {
      if (!to.route) return '/dashboard';
      var me = store.me;
      if (to.route.public) {
        if (me) return me.must_change_password ? '/password-required' : '/dashboard';
        return null;
      }
      if (!me) {
        var redirect = to.name === 'password-required' ? '' : to.fullPath;
        return '/login' + (redirect ? '?redirect=' + encodeURIComponent(redirect) : '');
      }
      if (me.must_change_password && to.name !== 'password-required') return '/password-required';
      if (!me.must_change_password && to.name === 'password-required') return '/dashboard';
      return null;
    });
  }

  var App = {
    name: 'AdminApp',
    template:
      '<div v-if="!ready || !route" class="app-boot"><div class="app-boot__spinner"></div><span>正在加载…</span></div>' +
      '<component v-else-if="route.bare" :is="route.component" :key="current.path" />' +
      '<pa-layout v-else :title="route.title" :active-menu="route.menu">' +
      '  <component :is="route.component" :key="current.path" />' +
      '</pa-layout>',
    setup: function () {
      var current = A.router.current;
      var route = computed(function () {
        return current.name ? A.router.byName(current.name) : null;
      });
      Vue.watch(route, function (r) {
        document.title = r && r.title ? r.title + ' - ' + BASE_TITLE : BASE_TITLE;
      });
      return {
        current: current,
        route: route,
        ready: computed(function () { return store.ready; })
      };
    }
  };

  var app = Vue.createApp(App);
  app.use(window.ElementPlus, { locale: window.ElementPlusLocaleZhCn });

  // 图标统一以 Icon 前缀注册（模板中写 <icon-user />），避免与 HTML 标签或页面组件重名
  var icons = {};
  Object.keys(window.ElementPlusIconsVue).forEach(function (name) {
    var comp = window.ElementPlusIconsVue[name];
    if (!comp || (typeof comp !== 'object' && typeof comp !== 'function')) return;
    icons[name] = comp;
    app.component('Icon' + name, comp);
  });
  app.config.globalProperties.$icons = Vue.markRaw(icons);

  app.component('PaBrandMark', A.components.BrandMark);
  app.component('PaError', A.components.ErrorState);
  app.component('PaDatetime', A.components.DateTime);
  app.component('PaChart', A.components.Chart);
  app.component('PaPasswordForm', A.components.PasswordForm);
  app.component('PaCropsInput', A.components.CropsInput);
  app.component('PaLayout', A.pages.Layout);

  app.config.errorHandler = function (err, instance, info) {
    if (window.console) console.error('[admin]', info, err);
  };

  app.mount('#app');
  A.router.start(guard);
})();
