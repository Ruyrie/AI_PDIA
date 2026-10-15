/*
 * 后台框架：左侧菜单 + 顶栏（管理员、修改密码、退出登录）+ 内容区。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var ref = Vue.ref;
  var computed = Vue.computed;

  var MENU = [
    { index: '/dashboard', title: '数据统计', icon: 'DataAnalysis' },
    { index: '/users', title: '用户管理', icon: 'User' },
    { index: '/config', title: '系统配置', icon: 'Setting' },
    { index: '/logs', title: '操作日志', icon: 'Tickets' }
  ];

  A.pages.Layout = {
    name: 'PaLayout',
    props: {
      title: String,
      activeMenu: String
    },
    template:
      '<el-container class="layout">' +
      '  <el-aside width="216px" class="layout-aside">' +
      '    <a class="aside-brand" href="#/dashboard">' +
      '      <pa-brand-mark :size="34" />' +
      '      <span class="aside-brand__text"><strong>病虫害识别助手</strong><small>管理后台</small></span>' +
      '    </a>' +
      '    <el-menu class="aside-menu" :default-active="activeMenu" @select="go">' +
      '      <el-menu-item v-for="m in menu" :key="m.index" :index="m.index">' +
      '        <el-icon><component :is="$icons[m.icon]" /></el-icon>' +
      '        <span>{{ m.title }}</span>' +
      '      </el-menu-item>' +
      '    </el-menu>' +
      '    <div class="aside-foot">pestai · admin</div>' +
      '  </el-aside>' +
      '  <el-container class="layout-body">' +
      '    <el-header height="56px" class="layout-header">' +
      '      <div class="header-title">{{ title }}</div>' +
      '      <div class="header-right">' +
      '        <span class="header-user">' +
      '          <el-avatar :size="28" class="header-user__avatar">{{ initial }}</el-avatar>' +
      '          <span class="header-user__name">{{ username }}</span>' +
      '        </span>' +
      '        <el-divider direction="vertical" />' +
      '        <el-button link :icon="$icons.Lock" @click="pwdVisible = true">修改密码</el-button>' +
      '        <el-button link :icon="$icons.SwitchButton" @click="logout">退出登录</el-button>' +
      '      </div>' +
      '    </el-header>' +
      '    <el-main class="layout-main">' +
      '      <slot />' +
      '    </el-main>' +
      '  </el-container>' +
      '  <el-dialog v-model="pwdVisible" title="修改密码" width="440px" :close-on-click-modal="false" destroy-on-close append-to-body>' +
      '    <pa-password-form cancelable @cancel="pwdVisible = false" @success="onPasswordChanged" />' +
      '  </el-dialog>' +
      '</el-container>',
    setup: function () {
      var pwdVisible = ref(false);

      var username = computed(function () {
        return (A.store.me && A.store.me.username) || '管理员';
      });
      var initial = computed(function () {
        return username.value.charAt(0).toUpperCase();
      });

      function go(index) {
        A.router.push(index);
      }

      function onPasswordChanged() {
        pwdVisible.value = false;
        // 刷新 csrf_token；若后端让旧会话失效，api.js 会在下次请求时带回登录页
        A.api.refreshMe().catch(function (err) {
          if (err && err.code === 40101) {
            A.message.info('密码已修改，请用新密码重新登录');
            A.router.replace('/login');
          }
        });
      }

      function logout() {
        A.confirmAction({
          title: '退出登录',
          message: '确定要退出管理后台吗？',
          type: 'info',
          confirmText: '退出',
          action: function () {
            return A.api.logout();
          }
        }).then(function (ok) {
          if (ok) A.router.replace('/login');
        });
      }

      return {
        menu: MENU, pwdVisible: pwdVisible, username: username, initial: initial,
        go: go, onPasswordChanged: onPasswordChanged, logout: logout
      };
    }
  };
})();
