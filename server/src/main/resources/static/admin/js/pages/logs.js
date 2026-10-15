/*
 * 操作日志（#/logs）。
 * 接口：GET logs?action=&page=&size= → {list:[{id, admin, action, target, detail, ip, created_at}], total, has_more}
 */
(function () {
  'use strict';

  var A = window.Admin;
  var reactive = Vue.reactive;
  var onMounted = Vue.onMounted;

  var query = reactive({ action: '', page: 1, size: 20 });

  A.pages.Logs = {
    name: 'PageLogs',
    template:
      '<div class="page">' +
      '  <section class="panel">' +
      '    <div class="filter-bar">' +
      '      <el-select v-model="query.action" class="filter-bar__action" clearable placeholder="全部动作" @change="search">' +
      '        <el-option v-for="a in actions" :key="a.value" :label="a.label" :value="a.value">' +
      '          <span>{{ a.label }}</span><span class="option-code">{{ a.value }}</span>' +
      '        </el-option>' +
      '      </el-select>' +
      '      <el-button :icon="$icons.Refresh" :loading="state.loading" @click="load">刷新</el-button>' +
      '      <div class="filter-bar__spacer"></div>' +
      '      <span class="filter-bar__meta" v-if="!state.error">共 {{ fmt.num(state.total) }} 条</span>' +
      '    </div>' +
      '    <el-table :data="state.list" v-loading="state.loading" class="data-table" row-key="id">' +
      '      <el-table-column label="时间" width="168">' +
      '        <template #default="{ row }"><span class="mono-num">{{ fmt.time(row.created_at) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="管理员" width="120" show-overflow-tooltip>' +
      '        <template #default="{ row }">{{ fmt.text(row.admin) }}</template>' +
      '      </el-table-column>' +
      '      <el-table-column label="动作" width="140">' +
      '        <template #default="{ row }">' +
      '          <el-tag :type="actionOf(row).type" size="small" effect="plain" disable-transitions :title="row.action">{{ actionOf(row).label }}</el-tag>' +
      '        </template>' +
      '      </el-table-column>' +
      '      <el-table-column label="对象" width="160" show-overflow-tooltip>' +
      '        <template #default="{ row }"><span class="mono">{{ fmt.text(row.target) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="详情" min-width="200" show-overflow-tooltip>' +
      '        <template #default="{ row }">{{ fmt.text(row.detail) }}</template>' +
      '      </el-table-column>' +
      '      <el-table-column label="IP" width="136">' +
      '        <template #default="{ row }"><span class="mono">{{ fmt.text(row.ip) }}</span></template>' +
      '      </el-table-column>' +
      '      <template #empty>' +
      '        <pa-error v-if="state.error" compact :message="state.error" @retry="load" />' +
      '        <el-empty v-else-if="!state.loading" :image-size="72" :description="query.action ? \'没有该类操作的日志\' : \'暂无操作日志\'" />' +
      '        <span v-else></span>' +
      '      </template>' +
      '    </el-table>' +
      '    <div class="pager" v-if="state.total > 0">' +
      '      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="state.total"' +
      '        :page-sizes="[20, 50, 100]" v-model:current-page="query.page" v-model:page-size="query.size"' +
      '        :disabled="state.loading" @current-change="load" @size-change="onSizeChange" />' +
      '    </div>' +
      '  </section>' +
      '</div>',
    setup: function () {
      var state = reactive({ list: [], total: 0, loading: false, error: '' });
      var seq = 0;

      function load() {
        var mySeq = ++seq;
        state.loading = true;
        state.error = '';
        return A.api.get('logs', { action: query.action || '', page: query.page, size: query.size }, { silent: true })
          .then(function (d) {
            if (mySeq !== seq) return;
            state.list = (d && d.list) || [];
            state.total = Number(d && d.total) || 0;
            var lastPage = Math.max(1, Math.ceil(state.total / query.size));
            if (!state.list.length && query.page > lastPage) {
              query.page = lastPage;
              load();
            }
          }, function (err) {
            if (mySeq !== seq) return;
            state.list = [];
            state.error = err.message;
          }).then(function () {
            if (mySeq === seq) state.loading = false;
          });
      }

      function search() {
        if (query.action == null) query.action = '';
        query.page = 1;
        load();
      }

      function onSizeChange() {
        query.page = 1;
        load();
      }

      function actionOf(row) {
        return A.labels.actionMap[row.action] || { label: A.fmt.text(row.action), type: 'info' };
      }

      onMounted(load);

      return {
        fmt: A.fmt, query: query, state: state, actions: A.labels.actions,
        load: load, search: search, onSizeChange: onSizeChange, actionOf: actionOf
      };
    }
  };
})();
