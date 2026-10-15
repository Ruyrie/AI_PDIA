/*
 * 管理后台 · 接口封装（约定见 docs/后台管理接口.md）。
 *
 * - 地址前缀 /admin/api/，响应 {code, message, data}，code = 0 成功，返回 data；
 * - 会话 Cookie（credentials: same-origin）；GET 以外的请求带 X-CSRF-Token；
 * - 40101 未登录 → 跳登录页；40302 初始密码未修改 → 跳强制改密页；
 * - 40301（CSRF 不符，多为令牌过期）→ 重新取一次 auth/me 再重试一次；
 * - 其他错误用 ElMessage 提示（opts.silent 时不提示，由页面自己显示）。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var BASE = '/admin/api/';

  /** 登录状态：me = {username, must_change_password}；csrf 只放内存 */
  var store = (A.store = Vue.reactive({
    ready: false,
    me: null,
    csrf: ''
  }));

  function ApiError(code, message, data, httpStatus) {
    var err = new Error(message);
    err.name = 'ApiError';
    err.code = code;
    err.data = data;
    err.httpStatus = httpStatus || 0;
    err.isApiError = true;
    return err;
  }

  function buildUrl(path, params) {
    var url = BASE + String(path).replace(/^\/+/, '');
    if (params) {
      var qs = new URLSearchParams();
      Object.keys(params).forEach(function (k) {
        var v = params[k];
        if (v !== undefined && v !== null && v !== '') qs.append(k, String(v));
      });
      var s = qs.toString();
      if (s) url += (url.indexOf('?') >= 0 ? '&' : '?') + s;
    }
    return url;
  }

  var HTTP_TO_CODE = { 401: 40101, 403: 40301, 429: 42901 };

  function httpMessage(status) {
    if (status === 401) return '请先登录';
    if (status === 403) return '没有权限执行该操作';
    if (status === 404) return '接口不存在（404）';
    if (status === 413) return '请求内容太大';
    if (status === 429) return '操作太频繁，请稍后再试';
    if (status === 502 || status === 503 || status === 504) return '服务暂时不可用（' + status + '），请稍后再试';
    if (status >= 500) return '服务器开小差了（' + status + '），请稍后再试';
    return '请求失败（HTTP ' + status + '）';
  }

  function rawRequest(method, path, opts) {
    var headers = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    var init = { method: method, headers: headers, credentials: 'same-origin', cache: 'no-store' };
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      if (store.csrf) headers['X-CSRF-Token'] = store.csrf;
      init.body = JSON.stringify(opts.body == null ? {} : opts.body);
    }
    return fetch(buildUrl(path, opts.params), init).then(function (res) {
      return res.text().then(function (text) {
        var payload = null;
        if (text) {
          try { payload = JSON.parse(text); } catch (e) { payload = null; }
        }
        if (payload && typeof payload === 'object' && typeof payload.code === 'number') {
          if (payload.code === 0) return payload.data === undefined ? null : payload.data;
          throw ApiError(payload.code, payload.message || ('请求失败（' + payload.code + '）'), payload.data, res.status);
        }
        if (res.ok) {
          throw ApiError(50001, '接口返回格式不正确', null, res.status);
        }
        throw ApiError(HTTP_TO_CODE[res.status] || 50001, httpMessage(res.status), null, res.status);
      });
    }, function () {
      throw ApiError(-1, '网络连接失败，请检查网络后重试');
    });
  }

  var redirectingToLogin = false;

  function onUnauthorized() {
    store.me = null;
    store.csrf = '';
    if (redirectingToLogin) return;
    var router = A.router;
    var current = router && router.current;
    if (current && current.name === 'login') return;
    redirectingToLogin = true;
    A.message.warning('登录已过期，请重新登录');
    var from = current && current.fullPath && current.name !== 'password-required' ? current.fullPath : '';
    router.replace('/login' + (from ? '?redirect=' + encodeURIComponent(from) : ''));
    setTimeout(function () { redirectingToLogin = false; }, 1500);
  }

  function onMustChangePassword() {
    if (store.me) store.me.must_change_password = true;
    var router = A.router;
    if (router && router.current.name !== 'password-required') {
      A.message.warning('请先修改初始密码');
      router.replace('/password-required');
    }
  }

  function handleError(err, opts) {
    if (err.code === 40101 && !opts.noAuthRedirect) {
      onUnauthorized();
      err.handled = true;
      return;
    }
    if (err.code === 40302 && !opts.noAuthRedirect) {
      onMustChangePassword();
      err.handled = true;
      return;
    }
    if (!opts.silent) {
      A.message.error(err.message);
      err.handled = true;
    }
  }

  function setMe(data) {
    if (!data) {
      store.me = null;
      store.csrf = '';
      return;
    }
    store.me = { username: data.username || '', must_change_password: !!data.must_change_password };
    if (data.csrf_token) store.csrf = data.csrf_token;
  }

  function request(method, path, opts) {
    opts = opts || {};
    return rawRequest(method, path, opts).catch(function (err) {
      if (!err || !err.isApiError) throw err;
      // CSRF 令牌不符：重新取一次再重试（请求未被执行，重试是安全的）
      if (err.code === 40301 && method !== 'GET' && !opts._retried && path !== 'auth/login') {
        return rawRequest('GET', 'auth/me', {}).then(function (me) {
          setMe(me);
          return request(method, path, Object.assign({}, opts, { _retried: true }));
        }, function () {
          handleError(err, opts);
          throw err;
        });
      }
      handleError(err, opts);
      throw err;
    });
  }

  A.api = {
    BASE: BASE,
    request: request,
    get: function (path, params, opts) {
      return request('GET', path, Object.assign({}, opts, { params: params }));
    },
    post: function (path, body, opts) {
      return request('POST', path, Object.assign({}, opts, { body: body }));
    },
    put: function (path, body, opts) {
      return request('PUT', path, Object.assign({}, opts, { body: body }));
    },
    del: function (path, body, opts) {
      return request('DELETE', path, Object.assign({}, opts, { body: body }));
    },
    setMe: setMe,
    /** 重新取当前管理员（同时刷新 csrf_token）；未登录时 me 置空并抛出 40101 */
    refreshMe: function () {
      return request('GET', 'auth/me', { silent: true, noAuthRedirect: true }).then(function (me) {
        setMe(me);
        return store.me;
      }, function (err) {
        if (err && err.code === 40101) setMe(null);
        throw err;
      });
    },
    logout: function () {
      return request('POST', 'auth/logout', { silent: true, noAuthRedirect: true }).catch(function () { return null; })
        .then(function () { setMe(null); });
    }
  };

  /** 路径参数编码 */
  A.enc = function (v) {
    return encodeURIComponent(String(v));
  };
})();
