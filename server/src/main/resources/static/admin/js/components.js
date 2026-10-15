/*
 * 管理后台 · 公共组件：品牌图标、错误状态、ECharts 图表、修改密码表单、作物标签输入。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var ref = Vue.ref;
  var reactive = Vue.reactive;
  var computed = Vue.computed;
  var watch = Vue.watch;
  var nextTick = Vue.nextTick;
  var onMounted = Vue.onMounted;
  var onBeforeUnmount = Vue.onBeforeUnmount;

  /** 品牌图标（叶片） */
  A.components.BrandMark = {
    name: 'PaBrandMark',
    props: { size: { type: Number, default: 32 } },
    template:
      '<svg class="pa-brand-mark" :width="size" :height="size" viewBox="0 0 32 32" aria-hidden="true">' +
      '<rect width="32" height="32" rx="8" fill="#456732"/>' +
      '<path d="M8.5 23.5C8.5 14.5 14 8.5 23.5 8.5C23.5 18 17.5 23.5 8.5 23.5Z" fill="#C5EFAB"/>' +
      '<path d="M8.5 23.5L18.5 13.5" stroke="#456732" stroke-width="1.6" stroke-linecap="round"/>' +
      '</svg>'
  };

  /** 时间两行显示（日期 / 时分秒），用于较窄的表格列；空值显示 — */
  A.components.DateTime = {
    name: 'PaDatetime',
    props: { value: String },
    template:
      '<span v-if="!parts" class="text-muted">—</span>' +
      '<span v-else class="pa-dt"><span>{{ parts[0] }}</span><span class="pa-dt__time">{{ parts[1] }}</span></span>',
    setup: function (props) {
      var parts = computed(function () {
        if (A.isBlank(props.value)) return null;
        var s = String(props.value).replace('T', ' ');
        var i = s.indexOf(' ');
        return i > 0 ? [s.slice(0, i), s.slice(i + 1)] : [s, ''];
      });
      return { parts: parts };
    }
  };

  /** 加载失败 + 重试 */
  A.components.ErrorState = {
    name: 'PaError',
    props: { message: String, compact: Boolean },
    emits: ['retry'],
    template:
      '<div class="pa-error" :class="{ \'is-compact\': compact }">' +
      '  <el-icon class="pa-error__icon"><icon-warning-filled /></el-icon>' +
      '  <div class="pa-error__text">{{ message || \'加载失败\' }}</div>' +
      '  <el-button size="small" :icon="$icons.Refresh" @click="$emit(\'retry\')">重试</el-button>' +
      '</div>'
  };

  /**
   * ECharts 图表：option 变化时重绘；窗口 / 容器尺寸变化时 resize；卸载时 dispose。
   * empty 为 true 时显示空状态。
   */
  A.components.Chart = {
    name: 'PaChart',
    props: {
      option: Object,
      height: { type: [Number, String], default: 300 },
      empty: Boolean,
      emptyText: { type: String, default: '暂无数据' }
    },
    template:
      '<div class="pa-chart" :style="{ height: cssHeight }">' +
      '  <div v-show="!empty" ref="el" class="pa-chart__canvas"></div>' +
      '  <div v-if="empty" class="pa-chart__empty"><el-empty :description="emptyText" :image-size="64" /></div>' +
      '</div>',
    setup: function (props) {
      var el = ref(null);
      var chart = null;
      var observer = null;

      var cssHeight = computed(function () {
        return typeof props.height === 'number' ? props.height + 'px' : props.height;
      });

      function render() {
        if (props.empty || !props.option || !el.value) return;
        if (!chart) chart = window.echarts.init(el.value, null, { renderer: 'canvas' });
        chart.setOption(props.option, true);
        chart.resize();
      }

      var resize = A.debounce(function () {
        if (chart && !props.empty) chart.resize();
      }, 120);

      onMounted(function () {
        nextTick(render);
        window.addEventListener('resize', resize);
        if (window.ResizeObserver && el.value) {
          observer = new ResizeObserver(resize);
          observer.observe(el.value);
        }
      });

      watch(function () { return [props.option, props.empty]; }, function () {
        nextTick(render);
      });

      onBeforeUnmount(function () {
        window.removeEventListener('resize', resize);
        if (observer) observer.disconnect();
        if (chart) chart.dispose();
        chart = null;
      });

      return { el: el, cssHeight: cssHeight };
    }
  };

  /**
   * 修改密码表单（强制改密页与「修改密码」弹窗共用）。
   * 规则：新密码 ≥ 10 位，同时含字母和数字，不能与原密码相同（后端同样校验）。
   */
  A.components.PasswordForm = {
    name: 'PaPasswordForm',
    props: {
      submitText: { type: String, default: '确认修改' },
      cancelable: Boolean,
      block: Boolean
    },
    emits: ['success', 'cancel'],
    template:
      '<el-form class="pwd-form" label-position="top" @submit.prevent>' +
      '  <el-form-item label="原密码" required>' +
      '    <el-input v-model="form.old" type="password" show-password autocomplete="current-password" maxlength="64" placeholder="请输入当前密码" @keyup.enter="submit" />' +
      '  </el-form-item>' +
      '  <el-form-item label="新密码" required>' +
      '    <el-input v-model="form.next" type="password" show-password autocomplete="new-password" maxlength="64" placeholder="至少 10 位，同时包含字母和数字" @keyup.enter="submit" />' +
      '  </el-form-item>' +
      '  <el-form-item label="确认新密码" required :error="confirmError">' +
      '    <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" maxlength="64" placeholder="再次输入新密码" @keyup.enter="submit" />' +
      '  </el-form-item>' +
      '  <ul class="pwd-rules">' +
      '    <li v-for="r in rules" :key="r.key" :class="{ \'is-ok\': r.ok }">' +
      '      <el-icon><icon-circle-check-filled v-if="r.ok" /><icon-remove v-else /></el-icon><span>{{ r.text }}</span>' +
      '    </li>' +
      '  </ul>' +
      '  <div class="pwd-actions" :class="{ \'is-block\': block }">' +
      '    <el-button v-if="cancelable" :disabled="loading" @click="$emit(\'cancel\')">取消</el-button>' +
      '    <el-button type="primary" :loading="loading" :disabled="!valid" @click="submit">{{ submitText }}</el-button>' +
      '  </div>' +
      '</el-form>',
    setup: function (props, ctx) {
      var form = reactive({ old: '', next: '', confirm: '' });
      var loading = ref(false);

      var rules = computed(function () {
        var n = form.next;
        return [
          { key: 'len', text: '至少 10 位', ok: n.length >= 10 },
          { key: 'mix', text: '同时包含字母和数字', ok: /[A-Za-z]/.test(n) && /\d/.test(n) },
          { key: 'diff', text: '不能与原密码相同', ok: n.length > 0 && n !== form.old },
          { key: 'same', text: '两次输入一致', ok: form.confirm.length > 0 && form.confirm === n }
        ];
      });

      var valid = computed(function () {
        return form.old.length > 0 && rules.value.every(function (r) { return r.ok; });
      });

      var confirmError = computed(function () {
        return form.confirm && form.next && form.confirm !== form.next ? '两次输入的新密码不一致' : '';
      });

      function submit() {
        if (loading.value || !valid.value) return;
        loading.value = true;
        A.api.post('auth/password', { old_password: form.old, new_password: form.next })
          .then(function () {
            if (A.store.me) A.store.me.must_change_password = false;
            form.old = '';
            form.next = '';
            form.confirm = '';
            A.message.success('密码已修改');
            ctx.emit('success');
          }, function () { /* 错误已提示 */ })
          .then(function () { loading.value = false; });
      }

      return { form: form, loading: loading, rules: rules, valid: valid, confirmError: confirmError, submit: submit };
    }
  };

  /**
   * 作物标签输入：v-model 为字符串数组；回车 / 逗号 / 顿号 / 失焦时把输入框内容加为标签。
   */
  A.components.CropsInput = {
    name: 'PaCropsInput',
    props: {
      modelValue: { type: Array, default: function () { return []; } },
      max: { type: Number, default: 10 },
      maxLength: { type: Number, default: 20 },
      placeholder: { type: String, default: '输入作物名后按回车' }
    },
    emits: ['update:modelValue'],
    template:
      '<div class="crops-input" :class="{ \'is-focus\': focused }" @click="focusInput">' +
      '  <el-tag v-for="(c, i) in modelValue" :key="c + i" closable disable-transitions @close="remove(i)">{{ c }}</el-tag>' +
      '  <input v-if="modelValue.length < max" ref="input" v-model="draft" class="crops-input__field" :maxlength="maxLength"' +
      '    :placeholder="modelValue.length ? \'\' : placeholder" @keydown="onKeydown" @focus="focused = true" @blur="onBlur"' +
      '    @compositionstart="composing = true" @compositionend="composing = false" />' +
      '  <span v-else class="crops-input__hint">最多 {{ max }} 项</span>' +
      '</div>',
    setup: function (props, ctx) {
      var draft = ref('');
      var input = ref(null);
      var focused = ref(false);
      var composing = ref(false);

      function commit() {
        var parts = A.splitCrops(draft.value);
        draft.value = '';
        if (!parts.length) return;
        var next = props.modelValue.slice();
        parts.forEach(function (p) {
          var item = p.slice(0, props.maxLength);
          if (next.length < props.max && next.indexOf(item) < 0) next.push(item);
        });
        ctx.emit('update:modelValue', next);
      }

      function remove(i) {
        var next = props.modelValue.slice();
        next.splice(i, 1);
        ctx.emit('update:modelValue', next);
      }

      function onKeydown(e) {
        if (composing.value || e.isComposing) return;
        if (e.key === 'Enter' || e.key === ',') {
          e.preventDefault();
          commit();
        } else if (e.key === 'Backspace' && !draft.value && props.modelValue.length) {
          remove(props.modelValue.length - 1);
        }
      }

      function onBlur() {
        focused.value = false;
        commit();
      }

      // 中文输入法打出的「，」「、」直接进入文本，这里拆分
      watch(draft, function (v) {
        if (!composing.value && /[,，、;；]/.test(v)) commit();
      });

      function focusInput() {
        if (input.value) input.value.focus();
      }

      return {
        draft: draft, input: input, focused: focused, composing: composing,
        remove: remove, onKeydown: onKeydown, onBlur: onBlur, focusInput: focusInput
      };
    }
  };
})();
