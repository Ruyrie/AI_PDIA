/*
 * 系统配置（#/config）：API Key 与模型。
 * 接口：GET config；PUT config/{key} {value, admin_password}；DELETE config/{key} {admin_password}；
 *      POST config/test/{group}（sms 时 {phone}）→ {ok, message, latency_ms}
 * 密钥类配置的修改 / 恢复必须提交当前管理员密码。
 * 「自动备用」分组（fallback，接口文档第八节）：AI_FALLBACK_ENABLED 用开关修改，备用模型名用修改弹窗；
 * 后端没有 fallback 测试时（40001 未知的配置分组）隐藏该卡片的「测试连接」。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var fmt = A.fmt;
  var ref = Vue.ref;
  var reactive = Vue.reactive;
  var computed = Vue.computed;
  var onMounted = Vue.onMounted;

  var GROUP_TITLE = A.labels.configGroup;

  /**
   * 卡片顺序固定；接口返回了未知分组时追加在最后。
   * optionalTest：后端不一定提供该分组的测试，返回「未知分组」时隐藏「测试连接」按钮。
   */
  var GROUPS = [
    { key: 'vision', title: GROUP_TITLE.vision, icon: 'Camera', desc: '拍照识别使用的视觉模型。DashScope API Key 同时用于语音识别。' },
    { key: 'chat', title: GROUP_TITLE.chat, icon: 'ChatDotRound', desc: 'AI 问答使用的对话模型。' },
    { key: 'asr', title: GROUP_TITLE.asr, icon: 'Microphone', desc: 'App 语音输入使用的识别模型，API Key 使用「通义千问识图」中的 DashScope API Key。' },
    {
      key: 'fallback', title: GROUP_TITLE.fallback, icon: 'Switch', optionalTest: true,
      desc: 'AI 问答用的 DeepSeek 出故障时自动改用通义千问；拍照识别用的通义千问出故障时自动改用 DeepSeek。用户无感知，只在后台记录。'
    },
    { key: 'sms', title: GROUP_TITLE.sms, icon: 'Message', desc: '注册、找回密码、换绑手机号等场景的短信验证码。' }
  ];

  /** 用开关显示的配置项（值为字符串 "true" / "false"）及确认框文字；其他非密钥的 *_ENABLED 项用通用文字 */
  var SWITCH_KEYS = {
    AI_FALLBACK_ENABLED: {
      name: '自动备用',
      on: '开启后，AI 问答或拍照识别的主模型出故障时，会自动改用备用模型完成（用户无感知，只在后台记录）。修改后立即生效，确定开启吗？',
      off: '关闭后，主模型出故障时不再改用备用模型，用户会直接看到失败提示。修改后立即生效，确定关闭吗？'
    }
  };

  /** 后端没有提供测试的分组：隐藏其「测试连接」，直到刷新浏览器（切换到其他页面再回来仍隐藏） */
  var noTest = reactive({});

  var PHONE_RE = /^1\d{10}$/;

  function isSwitchItem(it) {
    return !!it && (Object.prototype.hasOwnProperty.call(SWITCH_KEYS, it.key) ||
      (/_ENABLED$/.test(it.key || '') && !it.secret && /^(true|false)$/i.test(String(it.value || '').trim())));
  }

  function isOn(it) {
    return String((it && it.value) || '').trim().toLowerCase() === 'true';
  }

  /** config/test/{group} 返回「未知的配置分组：xxx」（40001）或接口不存在 */
  function isUnknownGroupError(err) {
    if (!err) return false;
    if (err.httpStatus === 404 || err.code === 40401) return true;
    return err.code === 40001 && /未知|分组|不支持|unknown|group/i.test(err.message || '');
  }

  A.pages.Config = {
    name: 'PageConfig',
    template:
      '<div class="page">' +
      '  <el-alert v-if="isHttp" class="page-alert" type="warning" show-icon :closable="false"' +
      '    title="当前通过 HTTP 访问，密钥会以明文在网络上传输，建议配置域名与 HTTPS 后再修改密钥" />' +
      '  <div class="page-toolbar">' +
      '    <div class="page-toolbar__hint">生效顺序：后台设置 → 环境变量 / .env → 默认值（前者优先）。修改后立即生效，不用重启。</div>' +
      '    <el-button :icon="$icons.Refresh" :loading="loading" @click="load">刷新</el-button>' +
      '  </div>' +

      '  <pa-error v-if="error && !items.length" :message="error" @retry="load" />' +
      '  <section v-else-if="loading && !items.length" class="panel panel--placeholder" v-loading="true"></section>' +
      '  <el-empty v-else-if="!items.length" description="没有可配置的项目" />' +
      '  <template v-else>' +
      '    <section v-for="g in groups" :key="g.key" class="panel config-group">' +
      '      <header class="panel__head config-group__head">' +
      '        <div class="config-group__title">' +
      '          <span class="stat-group__icon"><el-icon><component :is="$icons[g.icon]" /></el-icon></span>' +
      '          <div>' +
      '            <div class="panel__title">{{ g.title }}</div>' +
      '            <div class="panel__sub" v-if="g.desc">{{ g.desc }}</div>' +
      '          </div>' +
      '        </div>' +
      '        <el-button v-if="g.testable && !noTest[g.key]" :icon="$icons.Connection" :loading="!!testing[g.key]" @click="runTest(g)">测试连接</el-button>' +
      '      </header>' +
      '      <el-alert v-if="results[g.key]" class="config-test-result" :type="results[g.key].ok ? \'success\' : \'error\'" show-icon' +
      '        :closable="true" @close="results[g.key] = null"' +
      '        :title="(results[g.key].ok ? \'连接正常\' : \'连接失败\') + (results[g.key].latency !== null ? \'（耗时 \' + fmt.ms(results[g.key].latency) + \'）\' : \'\')"' +
      '        :description="results[g.key].message + \' · \' + results[g.key].at" />' +
      '      <div class="config-items">' +
      '        <div v-for="it in g.items" :key="it.key" class="config-item">' +
      '          <div class="config-item__name">' +
      '            <div class="config-item__label">' +
      '              {{ it.label || it.key }}' +
      '              <el-tag v-if="it.secret" size="small" type="warning" effect="plain" disable-transitions>密钥</el-tag>' +
      '            </div>' +
      '            <code class="config-item__key">{{ it.key }}</code>' +
      '          </div>' +
      '          <div v-if="isSwitchItem(it)" class="config-item__value config-item__switch">' +
      '            <el-switch :model-value="isOn(it) ? \'true\' : \'false\'" active-value="true" inactive-value="false"' +
      '              :loading="!!toggling[it.key]" :aria-label="it.label || it.key" @change="toggle(it)" />' +
      '            <span class="config-item__switch-text" :class="{ \'is-on\': isOn(it) }">{{ isOn(it) ? \'已开启\' : \'已关闭\' }}</span>' +
      '          </div>' +
      '          <div v-else class="config-item__value">' +
      '            <span v-if="it.value" class="mono config-item__text">{{ it.value }}</span>' +
      '            <span v-else class="text-muted">未配置</span>' +
      '          </div>' +
      '          <div class="config-item__source">' +
      '            <el-tag :type="sourceOf(it).type" size="small" disable-transitions :title="it.source">{{ sourceOf(it).text }}</el-tag>' +
      '            <div class="config-item__updated" v-if="it.updated_at || it.updated_by">' +
      '              {{ it.updated_by || \'—\' }} · {{ fmt.time(it.updated_at) }}' +
      '            </div>' +
      '          </div>' +
      '          <div class="config-item__actions">' +
      '            <el-button v-if="!isSwitchItem(it)" size="small" :icon="$icons.Edit" @click="openEdit(it)">修改</el-button>' +
      '            <el-button v-if="it.source === \'ADMIN\'" size="small" :icon="$icons.RefreshLeft" @click="openReset(it)">恢复默认</el-button>' +
      '          </div>' +
      '        </div>' +
      '      </div>' +
      '    </section>' +
      '  </template>' +

      '  <el-dialog v-model="dlg.visible" :title="dlg.mode === \'edit\' ? \'修改配置\' : \'恢复默认\'" width="540px"' +
      '    :close-on-click-modal="false" append-to-body @closed="clearDialog">' +
      '    <template v-if="dlg.item">' +
      '      <el-descriptions :column="1" border size="small" class="config-dlg-desc">' +
      '        <el-descriptions-item label="配置项">{{ dlg.item.label || dlg.item.key }}</el-descriptions-item>' +
      '        <el-descriptions-item label="Key"><code class="mono">{{ dlg.item.key }}</code></el-descriptions-item>' +
      '        <el-descriptions-item label="当前值"><span class="mono">{{ dlg.item.value || \'未配置\' }}</span></el-descriptions-item>' +
      '        <el-descriptions-item label="来源"><el-tag :type="sourceOf(dlg.item).type" size="small" disable-transitions>{{ sourceOf(dlg.item).text }}</el-tag></el-descriptions-item>' +
      '      </el-descriptions>' +
      '      <el-form label-position="top" class="dialog-form" @submit.prevent>' +
      '        <el-form-item v-if="dlg.mode === \'edit\'" label="新值" required :error="dlg.valueError">' +
      '          <el-input v-if="dlg.item.secret" v-model="dlg.value" type="password" show-password autocomplete="new-password"' +
      '            maxlength="2000" placeholder="请输入新的密钥（保存后只显示掩码）" @keyup.enter="submit" />' +
      '          <el-input v-else v-model="dlg.value" autocomplete="off" maxlength="2000" placeholder="请输入新值" @keyup.enter="submit" />' +
      '        </el-form-item>' +
      '        <p v-else class="dialog-text">恢复后不再使用后台设置的值，改回使用环境变量 / .env 中的值；都没有时使用默认值。</p>' +
      '        <el-form-item v-if="dlg.item.secret" label="管理员密码" required :error="dlg.passwordError">' +
      '          <el-input v-model="dlg.adminPassword" type="password" show-password autocomplete="current-password"' +
      '            maxlength="64" placeholder="请输入当前管理员的登录密码" @keyup.enter="submit" />' +
      '        </el-form-item>' +
      '      </el-form>' +
      '      <el-alert v-if="dlg.item.secret && isHttp" type="warning" :closable="false" show-icon' +
      '        title="当前为 HTTP 访问，密钥会以明文传输" />' +
      '    </template>' +
      '    <template #footer>' +
      '      <el-button :disabled="dlg.saving" @click="dlg.visible = false">取消</el-button>' +
      '      <el-button :type="dlg.mode === \'reset\' ? \'warning\' : \'primary\'" :loading="dlg.saving" @click="submit">' +
      '        {{ dlg.mode === \'edit\' ? \'保存\' : \'恢复默认\' }}' +
      '      </el-button>' +
      '    </template>' +
      '  </el-dialog>' +
      '</div>',
    setup: function () {
      var items = ref([]);
      var loading = ref(false);
      var error = ref('');
      var testing = reactive({});
      var results = reactive({});
      var toggling = reactive({});
      var dlg = reactive({
        visible: false, mode: 'edit', item: null, value: '', adminPassword: '',
        saving: false, valueError: '', passwordError: ''
      });
      var isHttp = window.location.protocol === 'http:';

      function load() {
        loading.value = true;
        error.value = '';
        return A.api.get('config', null, { silent: true }).then(function (d) {
          items.value = Array.isArray(d) ? d : [];
        }, function (err) {
          error.value = err.message;
          if (items.value.length) A.message.error(err.message);
        }).then(function () { loading.value = false; });
      }

      var groups = computed(function () {
        var known = {};
        var out = GROUPS.map(function (g) {
          known[g.key] = true;
          return Object.assign({}, g, {
            items: items.value.filter(function (it) { return it.group === g.key; }),
            testable: true
          });
        });
        var extra = {};
        items.value.forEach(function (it) {
          if (known[it.group]) return;
          if (!extra[it.group]) {
            extra[it.group] = { key: it.group || 'other', title: it.group || '其他', icon: 'Setting', desc: '', items: [], testable: false };
            out.push(extra[it.group]);
          }
          extra[it.group].items.push(it);
        });
        return out.filter(function (g) { return g.items.length; });
      });

      function sourceOf(it) {
        return A.labels.source[it && it.source] || { text: fmt.text(it && it.source), type: 'info' };
      }

      function replaceItem(updated) {
        if (!updated || !updated.key) {
          load();
          return;
        }
        items.value = items.value.map(function (it) {
          return it.key === updated.key ? Object.assign({}, it, updated) : it;
        });
      }

      function openEdit(it) {
        dlg.mode = 'edit';
        dlg.item = it;
        dlg.value = it.secret ? '' : (it.value || '');
        dlg.adminPassword = '';
        dlg.valueError = '';
        dlg.passwordError = '';
        dlg.visible = true;
      }

      function openReset(it) {
        dlg.mode = 'reset';
        dlg.item = it;
        dlg.value = '';
        dlg.adminPassword = '';
        dlg.valueError = '';
        dlg.passwordError = '';
        dlg.visible = true;
      }

      /** 开关：确认后 PUT config/{key} {value: "true" | "false"}（非密钥类，不需要管理员密码） */
      function toggle(it) {
        if (!it || toggling[it.key]) return;
        var next = isOn(it) ? 'false' : 'true';
        var turnOn = next === 'true';
        var text = SWITCH_KEYS[it.key];
        var name = (text && text.name) || it.label || it.key;
        A.confirmAction({
          title: (turnOn ? '开启' : '关闭') + name,
          message: text ? (turnOn ? text.on : text.off) : '修改后立即生效，确定' + (turnOn ? '开启' : '关闭') + '「' + name + '」吗？',
          type: turnOn ? 'info' : 'warning',
          confirmText: turnOn ? '开启' : '关闭',
          action: function () {
            toggling[it.key] = true;
            return A.api.put('config/' + A.enc(it.key), { value: next }).then(function (updated) {
              toggling[it.key] = false;
              replaceItem(updated);
            }, function (err) {
              toggling[it.key] = false;
              throw err;
            });
          }
        }).then(function (ok) {
          if (ok) A.message.success((turnOn ? '已开启' : '已关闭') + name + '，立即生效');
        });
      }

      function clearDialog() {
        dlg.value = '';
        dlg.adminPassword = '';
        dlg.item = null;
      }

      function submit() {
        var it = dlg.item;
        if (!it || dlg.saving) return;
        var value = (dlg.value || '').trim();
        dlg.valueError = '';
        dlg.passwordError = '';
        if (dlg.mode === 'edit' && !value) {
          dlg.valueError = '请输入新值';
          return;
        }
        if (it.secret && !dlg.adminPassword) {
          dlg.passwordError = '修改密钥需要输入管理员密码';
          return;
        }
        var path = 'config/' + A.enc(it.key);
        var body = {};
        if (it.secret) body.admin_password = dlg.adminPassword;
        var req;
        if (dlg.mode === 'edit') {
          body.value = value;
          req = A.api.put(path, body, { silent: true });
        } else {
          req = A.api.del(path, body, { silent: true });
        }
        dlg.saving = true;
        var mode = dlg.mode;
        req.then(function (updated) {
          replaceItem(updated);
          dlg.visible = false;
          A.message.success(mode === 'edit' ? '已保存，立即生效' : '已恢复默认');
        }, function (err) {
          if (err.handled) return;
          if (err.code === 40001 && it.secret && /密码/.test(err.message || '')) {
            dlg.passwordError = err.message;
          } else {
            A.message.error(err.message);
          }
        }).then(function () { dlg.saving = false; });
      }

      function showResult(groupKey, d) {
        var latency = d && d.latency_ms !== undefined && d.latency_ms !== null ? Number(d.latency_ms) : null;
        results[groupKey] = {
          ok: !!(d && d.ok),
          message: (d && d.message) || (d && d.ok ? '连接正常' : '连接失败'),
          latency: Number.isFinite(latency) ? latency : null,
          at: fmt.clock()
        };
      }

      function runTest(g) {
        if (testing[g.key]) return;
        if (g.key === 'sms') {
          A.confirmAction({
            title: '测试短信',
            message: '将使用当前配置向下面的手机号发送一条测试验证码（会产生短信费用）：',
            type: 'info',
            confirmText: '发送',
            prompt: {
              placeholder: '接收测试短信的手机号',
              validator: function (v) { return PHONE_RE.test((v || '').trim()) || '请输入 11 位手机号'; }
            },
            action: function (phone) {
              testing[g.key] = true;
              return A.api.post('config/test/sms', { phone: (phone || '').trim() }).then(function (d) {
                showResult(g.key, d);
              }).then(function () { testing[g.key] = false; }, function (err) {
                testing[g.key] = false;
                throw err;
              });
            }
          });
          return;
        }
        testing[g.key] = true;
        A.api.post('config/test/' + A.enc(g.key), null, { silent: !!g.optionalTest }).then(function (d) {
          showResult(g.key, d);
        }, function (err) {
          if (!g.optionalTest || err.handled) return; // 其他分组的错误已由 api.js 提示
          if (isUnknownGroupError(err)) {
            noTest[g.key] = true;
            results[g.key] = null;
            A.message.info('暂不支持单独测试「' + g.title + '」');
          } else {
            A.message.error(err.message);
          }
        }).then(function () { testing[g.key] = false; });
      }

      onMounted(load);

      return {
        fmt: fmt, isHttp: isHttp, items: items, loading: loading, error: error, groups: groups,
        testing: testing, results: results, toggling: toggling, noTest: noTest, dlg: dlg,
        load: load, sourceOf: sourceOf, isSwitchItem: isSwitchItem, isOn: isOn, toggle: toggle,
        openEdit: openEdit, openReset: openReset, clearDialog: clearDialog,
        submit: submit, runTest: runTest
      };
    }
  };
})();
