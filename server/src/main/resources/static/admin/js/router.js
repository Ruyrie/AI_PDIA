/*
 * 管理后台 · 极简 hash 路由：#/path/:param?query。
 * routes 由 app.js 注册；guard(to) 返回重定向路径（字符串）或空。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var routes = [];
  var guard = null;
  var seq = 0;

  /** 当前路由（响应式）：name / path / fullPath / params / query */
  var current = Vue.reactive({ name: '', path: '', fullPath: '', params: {}, query: {} });

  function compile(route) {
    var keys = [];
    var pattern = route.path.replace(/\/:([A-Za-z_][A-Za-z0-9_]*)/g, function (_, key) {
      keys.push(key);
      return '/([^/]+)';
    });
    route._re = new RegExp('^' + pattern + '/?$');
    route._keys = keys;
    return route;
  }

  function parseHash() {
    var raw = window.location.hash.replace(/^#/, '');
    if (!raw) raw = '/';
    if (raw.charAt(0) !== '/') raw = '/' + raw;
    var qi = raw.indexOf('?');
    var path = qi >= 0 ? raw.slice(0, qi) : raw;
    var query = {};
    if (qi >= 0) {
      new URLSearchParams(raw.slice(qi + 1)).forEach(function (v, k) { query[k] = v; });
    }
    return { path: path, query: query, fullPath: raw };
  }

  function match(path) {
    for (var i = 0; i < routes.length; i++) {
      var r = routes[i];
      var m = r._re.exec(path);
      if (m) {
        var params = {};
        r._keys.forEach(function (k, idx) {
          try { params[k] = decodeURIComponent(m[idx + 1]); } catch (e) { params[k] = m[idx + 1]; }
        });
        return { route: r, params: params };
      }
    }
    return null;
  }

  function resolve() {
    var mySeq = ++seq;
    var loc = parseHash();
    var m = match(loc.path);
    var to = {
      name: m ? m.route.name : '',
      route: m ? m.route : null,
      path: loc.path,
      fullPath: loc.fullPath,
      params: m ? m.params : {},
      query: loc.query
    };
    return Promise.resolve(guard ? guard(to) : null).then(function (redirect) {
      if (mySeq !== seq) return; // 期间又发生了跳转
      if (redirect && redirect !== loc.fullPath) {
        replace(redirect);
        return;
      }
      if (!m) return;
      current.name = to.name;
      current.path = to.path;
      current.fullPath = to.fullPath;
      current.params = to.params;
      current.query = to.query;
    });
  }

  function push(path) {
    if (window.location.hash === '#' + path) {
      resolve();
    } else {
      window.location.hash = path;
    }
  }

  function replace(path) {
    if (window.location.hash === '#' + path) {
      resolve();
    } else {
      window.location.replace(window.location.href.split('#')[0] + '#' + path);
    }
  }

  A.router = {
    current: current,
    routes: routes,
    add: function (list) {
      list.forEach(function (r) { routes.push(compile(r)); });
    },
    byName: function (name) {
      for (var i = 0; i < routes.length; i++) {
        if (routes[i].name === name) return routes[i];
      }
      return null;
    },
    push: push,
    replace: replace,
    start: function (guardFn) {
      guard = guardFn;
      window.addEventListener('hashchange', resolve);
      return resolve();
    }
  };
})();
