/*
 * 数据统计（#/dashboard）：概览卡片、趋势折线（7 / 30 天）、分布饼图与 Top 10 柱状图。
 * 接口：GET stats/overview、stats/trend?days=、stats/distribution
 * 模型自动备用（接口文档第八节）：overview.fallback {chat_7d, vision_7d}、trend.fallback_used；旧版后端没有这些字段时按 0 / 不画。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var fmt = A.fmt;
  var reactive = Vue.reactive;
  var computed = Vue.computed;
  var onMounted = Vue.onMounted;

  var INK = '#43483e';
  var INK_MUTED = '#74796d';
  var AXIS = '#c3c8bb';
  var GRID = '#eceee6';

  /**
   * 趋势各系列：颜色按固定顺序分配（已做色觉区分校验），并用不同标记形状作辅助区分。
   * optional：旧版后端没有该字段时不画这条线（备用模型与短信色在部分色觉下接近，另用虚线区分）。
   */
  var TREND_SERIES = [
    { key: 'new_users', name: '新增用户', color: '#4d7a35', symbol: 'circle' },
    { key: 'active_users', name: '活跃用户', color: '#2a78d6', symbol: 'rect' },
    { key: 'recognitions', name: '识别次数', color: '#eb6834', symbol: 'triangle' },
    { key: 'recognition_success', name: '识别成功', color: '#4a3aa7', symbol: 'diamond' },
    { key: 'ai_questions', name: 'AI 提问', color: '#eda100', symbol: 'roundRect' },
    { key: 'sms_sent', name: '短信', color: '#e87ba4', symbol: 'emptyCircle' },
    { key: 'fallback_used', name: '备用模型', color: '#1baf7a', symbol: 'emptyDiamond', dashed: true, optional: true }
  ];

  /** 近 7 天备用模型次数：旧版后端没有 fallback 字段时按 0 */
  function fallbackCount(d, key) {
    var n = Number(d && d.fallback && d.fallback[key]);
    return Number.isFinite(n) ? n : 0;
  }

  /** 分布图中「颜色跟着类别走」 */
  var CATEGORY_COLORS = {
    '病虫害': '#4d7a35',
    '病虫害识别': '#4d7a35',
    '作物 / 杂草': '#2a78d6',
    '作物与杂草识别': '#2a78d6',
    '种植户': '#4d7a35',
    '家庭园艺': '#2a78d6',
    '农技人员': '#eb6834',
    '未设置': '#b4b9ad',
    '其他': '#b4b9ad'
  };
  var FALLBACK_COLORS = ['#4d7a35', '#2a78d6', '#eb6834', '#4a3aa7', '#eda100', '#e87ba4'];

  var TOOLTIP_BASE = {
    backgroundColor: '#ffffff',
    borderColor: '#dfe4d7',
    borderWidth: 1,
    textStyle: { color: '#191d16', fontSize: 12 },
    extraCssText: 'box-shadow: 0 4px 16px rgba(25,29,22,.10); border-radius: 8px;'
  };

  /** [{name, value}] → 名称转中文，同名合并（如身份 null 与空串都算「未设置」） */
  function normList(list, nameMapper) {
    if (!Array.isArray(list)) return [];
    var out = [];
    var byName = {};
    list.forEach(function (it) {
      var raw = it && it.name;
      var name = nameMapper ? nameMapper(raw) : (A.isBlank(raw) ? '未设置' : String(raw));
      var value = Number(it && it.value) || 0;
      if (byName[name]) {
        byName[name].value += value;
      } else {
        byName[name] = { name: name, value: value };
        out.push(byName[name]);
      }
    });
    return out;
  }

  function isEmptyList(list) {
    return !list.length || list.every(function (it) { return !it.value; });
  }

  function trendOption(data) {
    var dates = (data && data.dates) || [];
    var many = dates.length > 10;
    var active = TREND_SERIES.filter(function (s) { return !s.optional || (data && Array.isArray(data[s.key])); });
    return {
      color: active.map(function (s) { return s.color; }),
      tooltip: Object.assign({}, TOOLTIP_BASE, {
        trigger: 'axis',
        axisPointer: { type: 'line', lineStyle: { color: '#9aa093' } }
      }),
      legend: {
        top: 0,
        left: 0,
        itemWidth: 14,
        itemHeight: 10,
        itemGap: 18,
        textStyle: { color: INK, fontSize: 12 },
        data: active.map(function (s) { return { name: s.name, icon: s.symbol }; })
      },
      grid: { left: 4, right: 16, top: 44, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates,
        axisLine: { lineStyle: { color: AXIS } },
        axisTick: { show: false },
        axisLabel: {
          color: INK_MUTED,
          hideOverlap: true,
          formatter: function (v) { return String(v).slice(5); }
        }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: GRID } },
        axisLabel: { color: INK_MUTED }
      },
      series: active.map(function (s) {
        var values = (data && data[s.key]) || [];
        return {
          name: s.name,
          type: 'line',
          data: dates.map(function (_, i) { return Number(values[i]) || 0; }),
          symbol: s.symbol,
          symbolSize: many ? 6 : 8,
          showSymbol: true,
          lineStyle: { width: 2, type: s.dashed ? 'dashed' : 'solid' },
          emphasis: { focus: 'series' }
        };
      })
    };
  }

  function pieOption(list) {
    var total = list.reduce(function (sum, it) { return sum + it.value; }, 0);
    return {
      tooltip: Object.assign({}, TOOLTIP_BASE, {
        trigger: 'item',
        formatter: function (p) {
          return p.marker + p.name + '<br/>' + fmt.num(p.value) + '（' + (total ? (p.value / total * 100).toFixed(1) : '0.0') + '%）';
        }
      }),
      legend: {
        bottom: 0,
        left: 'center',
        icon: 'circle',
        itemWidth: 10,
        itemHeight: 10,
        textStyle: { color: INK, fontSize: 12 }
      },
      series: [{
        type: 'pie',
        radius: ['45%', '68%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#ffffff', borderWidth: 2, borderRadius: 4 },
        label: {
          color: INK,
          lineHeight: 16,
          formatter: function (p) { return p.name + '\n' + (Number(p.percent) || 0).toFixed(1) + '%'; }
        },
        labelLine: { length: 8, length2: 10, lineStyle: { color: AXIS } },
        data: list.map(function (it, i) {
          return {
            name: it.name,
            value: it.value,
            itemStyle: { color: CATEGORY_COLORS[it.name] || FALLBACK_COLORS[i % FALLBACK_COLORS.length] }
          };
        })
      }]
    };
  }

  function barOption(list, color) {
    var data = list.slice(0, 10);
    return {
      tooltip: Object.assign({}, TOOLTIP_BASE, {
        trigger: 'axis',
        axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(69,103,50,0.06)' } },
        formatter: function (ps) {
          var p = ps && ps[0];
          return p ? p.name + '<br/>' + p.marker + fmt.num(p.value) : '';
        }
      }),
      grid: { left: 4, right: 44, top: 4, bottom: 4, containLabel: true },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: GRID } },
        axisLabel: { color: INK_MUTED }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: data.map(function (it) { return it.name; }),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: AXIS } },
        axisLabel: { color: INK, width: 112, overflow: 'truncate' }
      },
      series: [{
        type: 'bar',
        data: data.map(function (it) { return it.value; }),
        barMaxWidth: 16,
        itemStyle: { color: color, borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: INK_MUTED, fontSize: 12 }
      }]
    };
  }

  A.pages.Dashboard = {
    name: 'PageDashboard',
    template:
      '<div class="page">' +
      '  <div class="page-toolbar">' +
      '    <div class="page-toolbar__hint">「活跃」= 当天有登录、识别或 AI 提问之一<span v-if="updatedAt"> · 更新于 {{ updatedAt }}</span></div>' +
      '    <el-button :icon="$icons.Refresh" :loading="anyLoading" @click="loadAll">刷新</el-button>' +
      '  </div>' +

      '  <pa-error v-if="overview.error && !overview.data" :message="overview.error" @retry="loadOverview" />' +
      '  <div v-else class="stat-groups" v-loading="overview.loading && !overview.data">' +
      '    <section v-for="g in groups" :key="g.key" class="stat-group">' +
      '      <header class="stat-group__head">' +
      '        <span class="stat-group__icon" :class="\'is-\' + g.key"><el-icon><component :is="$icons[g.icon]" /></el-icon></span>' +
      '        <span class="stat-group__title">{{ g.title }}</span>' +
      '      </header>' +
      '      <div class="stat-group__items">' +
      '        <div v-for="it in g.items" :key="it.label" class="stat" :title="it.tip">' +
      '          <div class="stat__label">{{ it.label }}</div>' +
      '          <div class="stat__value" :class="{ \'is-alert\': it.alert }">{{ it.value }}</div>' +
      '        </div>' +
      '      </div>' +
      '    </section>' +
      '  </div>' +

      '  <section class="panel">' +
      '    <header class="panel__head">' +
      '      <div class="panel__title">趋势</div>' +
      '      <el-radio-group v-model="trend.days" size="small" :disabled="trend.loading" @change="loadTrend">' +
      '        <el-radio-button :label="7" :value="7">近 7 天</el-radio-button>' +
      '        <el-radio-button :label="30" :value="30">近 30 天</el-radio-button>' +
      '      </el-radio-group>' +
      '    </header>' +
      '    <pa-error v-if="trend.error" compact :message="trend.error" @retry="loadTrend" />' +
      '    <div v-else v-loading="trend.loading">' +
      '      <pa-chart :option="trendChart" :empty="trendEmpty" :height="340" />' +
      '    </div>' +
      '  </section>' +

      '  <pa-error v-if="dist.error" :message="dist.error" @retry="loadDist" />' +
      '  <template v-else>' +
      '    <div class="panel-grid">' +
      '      <section class="panel">' +
      '        <header class="panel__head"><div class="panel__title">识别模式</div></header>' +
      '        <div v-loading="dist.loading"><pa-chart :option="modesChart" :empty="!dist.loading && modesEmpty" empty-text="暂无识别记录" :height="280" /></div>' +
      '      </section>' +
      '      <section class="panel">' +
      '        <header class="panel__head"><div class="panel__title">种植身份</div></header>' +
      '        <div v-loading="dist.loading"><pa-chart :option="identitiesChart" :empty="!dist.loading && identitiesEmpty" empty-text="暂无用户" :height="280" /></div>' +
      '      </section>' +
      '    </div>' +
      '    <div class="panel-grid">' +
      '      <section class="panel">' +
      '        <header class="panel__head"><div class="panel__title">识别结果 Top 10</div><span class="panel__sub">仅统计识别成功的记录</span></header>' +
      '        <div v-loading="dist.loading"><pa-chart :option="resultsChart" :empty="!dist.loading && resultsEmpty" empty-text="暂无识别成功的记录" :height="320" /></div>' +
      '      </section>' +
      '      <section class="panel">' +
      '        <header class="panel__head"><div class="panel__title">种植作物 Top 10</div><span class="panel__sub">按用户资料中的主要作物</span></header>' +
      '        <div v-loading="dist.loading"><pa-chart :option="cropsChart" :empty="!dist.loading && cropsEmpty" empty-text="暂无用户填写作物" :height="320" /></div>' +
      '      </section>' +
      '    </div>' +
      '  </template>' +
      '</div>',
    setup: function () {
      var overview = reactive({ loading: false, error: '', data: null });
      var trend = reactive({ loading: false, error: '', data: null, days: 7 });
      var dist = reactive({ loading: false, error: '', data: null });
      var updated = reactive({ at: '' });

      function loadOverview() {
        overview.loading = true;
        overview.error = '';
        return A.api.get('stats/overview', null, { silent: true }).then(function (d) {
          overview.data = d || {};
          updated.at = fmt.clock();
        }, function (err) {
          overview.error = err.message;
        }).then(function () { overview.loading = false; });
      }

      function loadTrend() {
        trend.loading = true;
        trend.error = '';
        var days = trend.days;
        return A.api.get('stats/trend', { days: days }, { silent: true }).then(function (d) {
          if (days === trend.days) trend.data = d || {};
        }, function (err) {
          trend.error = err.message;
        }).then(function () { trend.loading = false; });
      }

      function loadDist() {
        dist.loading = true;
        dist.error = '';
        return A.api.get('stats/distribution', null, { silent: true }).then(function (d) {
          dist.data = d || {};
        }, function (err) {
          dist.error = err.message;
        }).then(function () { dist.loading = false; });
      }

      function loadAll() {
        loadOverview();
        loadTrend();
        loadDist();
      }

      onMounted(loadAll);

      var groups = computed(function () {
        var d = overview.data || {};
        var u = d.users || {};
        var r = d.recognitions || {};
        var ai = d.ai || {};
        var sms = d.sms || {};
        var fbVision = fallbackCount(d, 'vision_7d');
        var fbChat = fallbackCount(d, 'chat_7d');
        var hasData = !!overview.data;
        return [
          {
            key: 'users', title: '用户', icon: 'User', items: [
              { label: '总用户', value: fmt.num(u.total) },
              { label: '今日新增', value: fmt.num(u.today_new) },
              { label: '近 7 天活跃', value: fmt.num(u.active_7d) },
              { label: '已停用', value: fmt.num(u.disabled), alert: Number(u.disabled) > 0 }
            ]
          },
          {
            key: 'recognitions', title: '拍照识别', icon: 'Camera', items: [
              { label: '识别总数', value: fmt.num(r.total) },
              { label: '今日识别', value: fmt.num(r.today) },
              { label: '近 7 天成功率', value: fmt.percent(r.success_rate_7d) },
              {
                label: '近 7 天备用模型', value: hasData ? fmt.num(fbVision) : fmt.DASH, alert: fbVision > 0,
                tip: '近 7 天主模型（通义千问）出故障、改由备用模型（DeepSeek）完成的识别次数'
              }
            ]
          },
          {
            key: 'ai', title: 'AI 问答', icon: 'ChatDotRound', items: [
              { label: '提问总数', value: fmt.num(ai.questions_total) },
              { label: '今日提问', value: fmt.num(ai.questions_today) },
              { label: '近 7 天带图占比', value: fmt.percent(ai.image_question_ratio_7d) },
              {
                label: '近 7 天备用模型', value: hasData ? fmt.num(fbChat) : fmt.DASH, alert: fbChat > 0,
                tip: '近 7 天主模型（DeepSeek）出故障、改由备用模型（通义千问）完成的回答数'
              }
            ]
          },
          {
            key: 'sms', title: '短信', icon: 'Message', items: [
              { label: '今日发送', value: fmt.num(sms.today) },
              { label: '近 7 天发送', value: fmt.num(sms.last_7d) }
            ]
          }
        ];
      });

      var trendChart = computed(function () { return trendOption(trend.data); });
      var trendEmpty = computed(function () {
        return !trend.loading && !(trend.data && Array.isArray(trend.data.dates) && trend.data.dates.length);
      });

      var modes = computed(function () {
        return normList(dist.data && dist.data.modes, function (n) { return A.isBlank(n) ? '未知' : A.modeText(n); });
      });
      var identities = computed(function () {
        return normList(dist.data && dist.data.identities, function (n) { return A.fmt.identity(n); });
      });
      var results = computed(function () { return normList(dist.data && dist.data.top_results); });
      var crops = computed(function () { return normList(dist.data && dist.data.crops); });

      return {
        overview: overview, trend: trend, dist: dist,
        updatedAt: computed(function () { return updated.at; }),
        anyLoading: computed(function () { return overview.loading || trend.loading || dist.loading; }),
        groups: groups,
        trendChart: trendChart, trendEmpty: trendEmpty,
        modesChart: computed(function () { return pieOption(modes.value); }),
        modesEmpty: computed(function () { return isEmptyList(modes.value); }),
        identitiesChart: computed(function () { return pieOption(identities.value); }),
        identitiesEmpty: computed(function () { return isEmptyList(identities.value); }),
        resultsChart: computed(function () { return barOption(results.value, '#4d7a35'); }),
        resultsEmpty: computed(function () { return isEmptyList(results.value); }),
        cropsChart: computed(function () { return barOption(crops.value, '#2a78d6'); }),
        cropsEmpty: computed(function () { return isEmptyList(crops.value); }),
        loadOverview: loadOverview, loadTrend: loadTrend, loadDist: loadDist, loadAll: loadAll
      };
    }
  };
})();
