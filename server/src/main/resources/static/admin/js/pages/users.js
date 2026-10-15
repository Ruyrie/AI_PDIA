/*
 * 用户管理 · 列表（#/users）。
 * 接口：GET users?keyword=&status=&page=&size= → {list:[UserRow], total, has_more}
 * 筛选条件与页码保存在模块内，从详情页返回时保持不变。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var reactive = Vue.reactive;
  var onMounted = Vue.onMounted;

  var query = reactive({ keyword: '', status: 'ALL', page: 1, size: 20 });

  A.pages.Users = {
    name: 'PageUsers',
    template:
      '<div class="page">' +
      '  <section class="panel">' +
      '    <div class="filter-bar">' +
      '      <el-input v-model="form.keyword" class="filter-bar__keyword" clearable :prefix-icon="$icons.Search"' +
      '        placeholder="手机号 / 昵称" maxlength="32" @keyup.enter="search" @clear="search" />' +
      '      <el-select v-model="form.status" class="filter-bar__status" @change="search">' +
      '        <el-option label="全部状态" value="ALL" />' +
      '        <el-option label="正常" value="NORMAL" />' +
      '        <el-option label="已停用" value="DISABLED" />' +
      '      </el-select>' +
      '      <el-button type="primary" :icon="$icons.Search" :loading="loading" @click="search">查询</el-button>' +
      '      <el-button :disabled="loading" @click="reset">重置</el-button>' +
      '      <div class="filter-bar__spacer"></div>' +
      '      <span class="filter-bar__meta" v-if="!error">共 {{ fmt.num(total) }} 位用户</span>' +
      '    </div>' +
      '    <el-table :data="list" v-loading="loading" class="data-table" row-key="id" @row-dblclick="openDetail">' +
      '      <el-table-column label="头像" width="60" align="center">' +
      '        <template #default="{ row }">' +
      '          <el-avatar :size="32" :src="row.avatar_url || undefined" class="user-avatar">{{ initial(row) }}</el-avatar>' +
      '        </template>' +
      '      </el-table-column>' +
      '      <el-table-column label="昵称" min-width="90" show-overflow-tooltip>' +
      '        <template #default="{ row }">{{ fmt.text(row.nickname) }}</template>' +
      '      </el-table-column>' +
      '      <el-table-column label="手机号" width="120">' +
      '        <template #default="{ row }"><span class="mono">{{ fmt.text(row.phone) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="身份" width="88">' +
      '        <template #default="{ row }"><span :class="{ \'text-muted\': !row.identity }">{{ fmt.identity(row.identity) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="作物" min-width="100" show-overflow-tooltip>' +
      '        <template #default="{ row }">{{ fmt.crops(row.crops) }}</template>' +
      '      </el-table-column>' +
      '      <el-table-column label="状态" width="78">' +
      '        <template #default="{ row }">' +
      '          <el-tag :type="statusOf(row).type" size="small" disable-transitions>{{ statusOf(row).text }}</el-tag>' +
      '        </template>' +
      '      </el-table-column>' +
      '      <el-table-column label="注册时间" width="108">' +
      '        <template #default="{ row }"><pa-datetime :value="row.created_at" /></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="最近登录" width="108">' +
      '        <template #default="{ row }"><pa-datetime :value="row.last_login_at" /></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="识别数" width="72" align="right">' +
      '        <template #default="{ row }"><span class="mono-num">{{ fmt.num(row.recognition_count) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="提问数" width="72" align="right">' +
      '        <template #default="{ row }"><span class="mono-num">{{ fmt.num(row.ai_question_count) }}</span></template>' +
      '      </el-table-column>' +
      '      <el-table-column label="操作" width="68" align="center" fixed="right">' +
      '        <template #default="{ row }">' +
      '          <el-button link type="primary" @click="openDetail(row)">详情</el-button>' +
      '        </template>' +
      '      </el-table-column>' +
      '      <template #empty>' +
      '        <pa-error v-if="error" compact :message="error" @retry="load" />' +
      '        <el-empty v-else-if="!loading" :image-size="72" :description="hasFilter ? \'没有符合条件的用户\' : \'暂无用户\'" />' +
      '        <span v-else></span>' +
      '      </template>' +
      '    </el-table>' +
      '    <div class="pager" v-if="total > 0">' +
      '      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"' +
      '        :page-sizes="[10, 20, 50, 100]" v-model:current-page="query.page" v-model:page-size="query.size"' +
      '        :disabled="loading" @current-change="load" @size-change="onSizeChange" />' +
      '    </div>' +
      '  </section>' +
      '</div>',
    setup: function () {
      var form = reactive({ keyword: query.keyword, status: query.status });
      var state = reactive({ list: [], total: 0, loading: false, error: '' });
      var seq = 0;

      function load() {
        var mySeq = ++seq;
        state.loading = true;
        state.error = '';
        return A.api.get('users', {
          keyword: query.keyword,
          status: query.status === 'ALL' ? '' : query.status,
          page: query.page,
          size: query.size
        }, { silent: true }).then(function (d) {
          if (mySeq !== seq) return;
          state.list = (d && d.list) || [];
          state.total = Number(d && d.total) || 0;
          // 当前页已被删空（如注销后返回）时回到上一页
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
        query.keyword = (form.keyword || '').trim();
        query.status = form.status;
        query.page = 1;
        load();
      }

      function reset() {
        form.keyword = '';
        form.status = 'ALL';
        search();
      }

      function onSizeChange() {
        query.page = 1;
        load();
      }

      function openDetail(row) {
        A.router.push('/users/' + A.enc(row.id));
      }

      function statusOf(row) {
        return A.labels.userStatus[row.status] || { text: A.fmt.text(row.status), type: 'info' };
      }

      function initial(row) {
        var n = (row.nickname || '').trim();
        return n ? n.charAt(0) : '用';
      }

      onMounted(load);

      return {
        fmt: A.fmt,
        query: query,
        form: form,
        list: Vue.toRef(state, 'list'),
        total: Vue.toRef(state, 'total'),
        loading: Vue.toRef(state, 'loading'),
        error: Vue.toRef(state, 'error'),
        hasFilter: Vue.computed(function () { return !!(query.keyword || query.status !== 'ALL'); }),
        load: load, search: search, reset: reset, onSizeChange: onSizeChange,
        openDetail: openDetail, statusOf: statusOf, initial: initial
      };
    }
  };
})();
