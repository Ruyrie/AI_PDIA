/*
 * 登录（#/login）与强制修改初始密码（#/password-required）。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var ref = Vue.ref;
  var reactive = Vue.reactive;
  var onMounted = Vue.onMounted;

  /** 登录后跳转：只接受站内 hash 路径，且不回到登录 / 改密页 */
  function safeRedirect(target) {
    if (!target || target.charAt(0) !== '/' || target.charAt(1) === '/') return '/dashboard';
    if (/^\/(login|password-required)\b/.test(target)) return '/dashboard';
    return target;
  }

  A.pages.Login = {
    name: 'PageLogin',
    template:
      '<div class="auth-page">' +
      '  <div class="auth-card">' +
      '    <div class="auth-brand">' +
      '      <pa-brand-mark :size="44" />' +
      '      <div>' +
      '        <h1>病虫害识别助手</h1>' +
      '        <p>管理后台</p>' +
      '      </div>' +
      '    </div>' +
      '    <el-form class="auth-form" label-position="top" size="large" @submit.prevent>' +
      '      <el-form-item label="用户名">' +
      '        <el-input ref="userInput" v-model.trim="form.username" :prefix-icon="$icons.User" placeholder="请输入用户名"' +
      '          autocomplete="username" maxlength="64" @keyup.enter="submit" />' +
      '      </el-form-item>' +
      '      <el-form-item label="密码">' +
      '        <el-input ref="pwdInput" v-model="form.password" :prefix-icon="$icons.Lock" type="password" show-password placeholder="请输入密码"' +
      '          autocomplete="current-password" maxlength="64" @keyup.enter="submit" />' +
      '      </el-form-item>' +
      '      <el-alert v-if="error" class="auth-alert" :title="error" :type="errorType" show-icon :closable="false" />' +
      '      <el-button type="primary" size="large" class="auth-submit" :loading="loading" @click="submit">登 录</el-button>' +
      '    </el-form>' +
      '    <p class="auth-foot">仅限授权管理员使用，所有操作都会记录在操作日志中</p>' +
      '  </div>' +
      '</div>',
    setup: function () {
      var form = reactive({ username: '', password: '' });
      var loading = ref(false);
      var error = ref('');
      var errorType = ref('error');
      var userInput = ref(null);
      var pwdInput = ref(null);

      onMounted(function () {
        if (userInput.value) userInput.value.focus();
      });

      function submit() {
        if (loading.value) return;
        if (!form.username) {
          error.value = '请输入用户名';
          errorType.value = 'warning';
          if (userInput.value) userInput.value.focus();
          return;
        }
        if (!form.password) {
          error.value = '请输入密码';
          errorType.value = 'warning';
          if (pwdInput.value) pwdInput.value.focus();
          return;
        }
        loading.value = true;
        error.value = '';
        A.api.post('auth/login', { username: form.username, password: form.password }, { silent: true, noAuthRedirect: true })
          .then(function (data) {
            A.api.setMe(data);
            form.password = '';
            if (A.store.me && A.store.me.must_change_password) {
              A.router.replace('/password-required');
            } else {
              A.router.replace(safeRedirect(A.router.current.query.redirect));
            }
          }, function (err) {
            form.password = '';
            errorType.value = err && err.code === 42901 ? 'warning' : 'error';
            error.value = (err && err.message) || '登录失败，请稍后再试';
            if (pwdInput.value) pwdInput.value.focus();
          })
          .then(function () { loading.value = false; });
      }

      return {
        form: form, loading: loading, error: error, errorType: errorType,
        userInput: userInput, pwdInput: pwdInput, submit: submit
      };
    }
  };

  A.pages.PasswordRequired = {
    name: 'PagePasswordRequired',
    template:
      '<div class="auth-page">' +
      '  <div class="auth-card auth-card--wide">' +
      '    <div class="auth-brand">' +
      '      <pa-brand-mark :size="44" />' +
      '      <div>' +
      '        <h1>修改初始密码</h1>' +
      '        <p>{{ username }}，首次登录需要先设置新的密码</p>' +
      '      </div>' +
      '    </div>' +
      '    <el-alert class="auth-alert" type="warning" show-icon :closable="false"' +
      '      title="为了后台安全，修改初始密码前不能使用其他功能。" />' +
      '    <pa-password-form submit-text="修改密码并进入后台" block @success="done" />' +
      '    <div class="auth-switch">' +
      '      <el-button link :disabled="leaving" @click="logout">退出登录</el-button>' +
      '    </div>' +
      '  </div>' +
      '</div>',
    setup: function () {
      var leaving = ref(false);
      var username = Vue.computed(function () {
        return (A.store.me && A.store.me.username) || '管理员';
      });

      function done() {
        // 改密后刷新会话信息（csrf_token 可能更换）；若后端已让会话失效，则回到登录页
        A.api.refreshMe().then(function () {
          A.router.replace('/dashboard');
        }, function (err) {
          if (err && err.code === 40101) {
            A.message.info('密码已修改，请用新密码重新登录');
            A.router.replace('/login');
          } else {
            A.router.replace('/dashboard');
          }
        });
      }

      function logout() {
        leaving.value = true;
        A.api.logout().then(function () {
          leaving.value = false;
          A.router.replace('/login');
        });
      }

      return { username: username, leaving: leaving, done: done, logout: logout };
    }
  };
})();
