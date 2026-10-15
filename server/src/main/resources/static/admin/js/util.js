/*
 * 管理后台 · 公共工具：命名空间、格式化、标签文字、复制、确认框。
 * 依赖（全局）：Vue、ElementPlus（库文件放在 /admin/vendor/，见 index.html 与 vendor/README.md）。
 */
(function () {
  'use strict';

  var missing = [];
  if (typeof window.Vue === 'undefined') missing.push('vue');
  if (typeof window.ElementPlus === 'undefined') missing.push('element-plus');
  if (typeof window.ElementPlusIconsVue === 'undefined') missing.push('@element-plus/icons-vue');
  if (typeof window.echarts === 'undefined') missing.push('echarts');
  if (missing.length) {
    var root = document.getElementById('app');
    if (root) {
      root.innerHTML = '<div class="app-fatal"><strong>页面资源加载失败</strong>' +
        '<p>无法加载：' + missing.join('、') + '（/admin/vendor/…）。请确认后端 jar 中包含 static/admin/vendor 目录后刷新页面。</p></div>';
    }
    throw new Error('Admin console: missing libraries: ' + missing.join(', '));
  }

  var A = (window.Admin = window.Admin || {});
  A.pages = A.pages || {};
  A.components = A.components || {};

  var DASH = '—';
  var numberFormat = new Intl.NumberFormat('zh-CN');

  function isBlank(v) {
    return v === null || v === undefined || String(v).trim() === '';
  }

  /** 格式化 */
  A.fmt = {
    DASH: DASH,
    /** 12345 → 12,345；空 → — */
    num: function (v) {
      if (isBlank(v)) return DASH;
      var n = Number(v);
      return Number.isFinite(n) ? numberFormat.format(n) : DASH;
    },
    /** 0.873 → 87.3% */
    percent: function (v, digits) {
      if (isBlank(v)) return DASH;
      var n = Number(v);
      if (!Number.isFinite(n)) return DASH;
      return (n * 100).toFixed(digits === undefined ? 1 : digits) + '%';
    },
    text: function (v) {
      return isBlank(v) ? DASH : String(v);
    },
    time: function (v) {
      return isBlank(v) ? DASH : String(v).replace('T', ' ');
    },
    /** "番茄,黄瓜" → "番茄、黄瓜" */
    crops: function (v) {
      var list = A.splitCrops(v);
      return list.length ? list.join('、') : DASH;
    },
    identity: function (v) {
      return isBlank(v) ? '未设置' : String(v);
    },
    ms: function (v) {
      if (isBlank(v)) return DASH;
      var n = Number(v);
      return Number.isFinite(n) ? numberFormat.format(Math.round(n)) + ' ms' : DASH;
    },
    /** 当前时间 HH:mm:ss */
    clock: function () {
      var d = new Date();
      function p(x) { return (x < 10 ? '0' : '') + x; }
      return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
  };

  A.isBlank = isBlank;

  /** 逗号（含中文逗号、顿号、分号）分隔的作物字符串 → 去空去重的数组 */
  A.splitCrops = function (v) {
    if (Array.isArray(v)) return v.filter(function (x) { return !isBlank(x); });
    if (isBlank(v)) return [];
    var seen = {};
    return String(v).split(/[,，、;；]/).map(function (s) { return s.trim(); }).filter(function (s) {
      if (!s || seen[s]) return false;
      seen[s] = true;
      return true;
    });
  };

  /** 枚举值 → 中文与标签颜色 */
  A.labels = {
    identities: ['种植户', '家庭园艺', '农技人员'],
    mode: { PEST: '病虫害', CROP: '作物 / 杂草' },
    userStatus: {
      NORMAL: { text: '正常', type: 'success' },
      DISABLED: { text: '已停用', type: 'danger' }
    },
    recStatus: {
      SUCCESS: { text: '识别成功', type: 'success' },
      IMAGE_BAD: { text: '照片不清晰', type: 'warning' },
      FAILED: { text: '识别失败', type: 'danger' }
    },
    severity: { '健康': 'success', '轻度': 'primary', '中度': 'warning', '较重': 'danger' },
    source: {
      ADMIN: { text: '后台设置', type: 'primary' },
      ENV: { text: '环境变量', type: 'success' },
      DEFAULT: { text: '默认值', type: 'info' },
      NONE: { text: '未配置', type: 'danger' }
    },
    /** 系统配置分组（config 的 group，也是 config/test/{group} 与 CONFIG_TEST 日志的对象） */
    configGroup: {
      vision: '通义千问识图',
      chat: 'DeepSeek 问答',
      asr: '语音识别',
      fallback: '自动备用',
      sms: '阿里云短信'
    },
    /** 由备用模型完成的识别 / 回答（模型自动备用，见接口文档第八节） */
    fallback: { text: '备用', type: 'warning', tip: '主模型出错，由备用模型完成' },
    actions: [
      { value: 'LOGIN', label: '登录', type: 'success' },
      { value: 'LOGIN_FAILED', label: '登录失败', type: 'danger' },
      { value: 'LOGOUT', label: '退出登录', type: 'info' },
      { value: 'CHANGE_PASSWORD', label: '修改密码', type: 'warning' },
      { value: 'USER_DISABLE', label: '停用用户', type: 'warning' },
      { value: 'USER_ENABLE', label: '启用用户', type: 'success' },
      { value: 'USER_LOGOUT', label: '强制下线', type: 'warning' },
      { value: 'USER_PROFILE', label: '编辑用户资料', type: 'info' },
      { value: 'USER_RESET_PASSWORD', label: '重置用户密码', type: 'warning' },
      { value: 'USER_DEACTIVATE', label: '注销用户', type: 'danger' },
      { value: 'RECOGNITION_DELETE', label: '删除识别记录', type: 'danger' },
      { value: 'SESSION_DELETE', label: '删除 AI 对话', type: 'danger' },
      { value: 'CONFIG_UPDATE', label: '修改配置', type: 'warning' },
      { value: 'CONFIG_RESET', label: '恢复默认配置', type: 'warning' },
      { value: 'CONFIG_TEST', label: '测试连接', type: 'info' }
    ]
  };

  A.labels.actionMap = {};
  A.labels.actions.forEach(function (a) { A.labels.actionMap[a.value] = a; });

  A.modeText = function (v) {
    return A.labels.mode[v] || A.fmt.text(v);
  };

  A.debounce = function (fn, wait) {
    var t = null;
    return function () {
      var args = arguments;
      var self = this;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(self, args); }, wait);
    };
  };

  /**
   * 复制文本。HTTP（非安全上下文）下没有 navigator.clipboard，退回到 execCommand('copy')。
   * container：在弹窗里复制时传入弹窗内的元素，避免焦点陷阱把选区抢走。
   */
  A.copyText = function (text, container) {
    var value = String(text == null ? '' : text);
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(value).then(function () { return true; }, function () {
        return fallbackCopy(value, container);
      });
    }
    return Promise.resolve(fallbackCopy(value, container));
  };

  function fallbackCopy(value, container) {
    var host = container || document.body;
    var ta = document.createElement('textarea');
    ta.value = value;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.left = '-1000px';
    ta.style.opacity = '0';
    host.appendChild(ta);
    ta.focus();
    ta.select();
    try { ta.setSelectionRange(0, value.length); } catch (e) { /* ignore */ }
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    host.removeChild(ta);
    return ok;
  }

  /**
   * 确认框：点「确定」后执行 action，执行期间按钮转圈；action 抛错时保持打开（错误已由 api.js 提示）。
   * o: {title, message, confirmText, type, danger, prompt:{placeholder, validator, errorMessage, inputType}, action(inputValue)}
   * 返回 Promise<boolean>（是否执行成功）。
   */
  A.confirmAction = function (o) {
    var ElMessageBox = window.ElementPlus.ElMessageBox;
    var succeeded = false;
    var options = {
      type: o.type || 'warning',
      confirmButtonText: o.confirmText || '确定',
      cancelButtonText: '取消',
      closeOnClickModal: false,
      closeOnPressEscape: true,
      autofocus: false,
      beforeClose: function (action, instance, done) {
        if (action !== 'confirm') {
          done();
          return;
        }
        if (instance.confirmButtonLoading) return;
        instance.confirmButtonLoading = true;
        Promise.resolve()
          .then(function () { return o.action ? o.action(instance.inputValue) : null; })
          .then(function () {
            succeeded = true;
            instance.confirmButtonLoading = false;
            done();
          }, function () {
            instance.confirmButtonLoading = false;
          });
      }
    };
    if (o.danger) options.confirmButtonClass = 'el-button--danger';
    var opener;
    if (o.prompt) {
      options.inputPlaceholder = o.prompt.placeholder || '';
      options.inputType = o.prompt.inputType || 'text';
      options.inputValue = o.prompt.value || '';
      if (o.prompt.validator) options.inputValidator = o.prompt.validator;
      if (o.prompt.errorMessage) options.inputErrorMessage = o.prompt.errorMessage;
      opener = ElMessageBox.prompt(o.message, o.title, options);
    } else {
      opener = ElMessageBox.confirm(o.message, o.title, options);
    }
    return opener.then(function () { return succeeded; }, function () { return false; });
  };

  A.message = {
    success: function (text) { window.ElementPlus.ElMessage({ type: 'success', message: text, grouping: true }); },
    warning: function (text) { window.ElementPlus.ElMessage({ type: 'warning', message: text, grouping: true }); },
    error: function (text) { window.ElementPlus.ElMessage({ type: 'error', message: text, grouping: true, duration: 4000 }); },
    info: function (text) { window.ElementPlus.ElMessage({ type: 'info', message: text, grouping: true }); }
  };
})();
