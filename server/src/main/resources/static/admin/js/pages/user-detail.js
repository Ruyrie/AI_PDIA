/*
 * 用户管理 · 详情（#/users/:id）：资料、停用 / 启用、强制下线、编辑资料、重置密码、注销账号；
 * 识别记录与 AI 对话（只读查看、删除）。
 * 识别记录与 AI 回答显示所用模型 model，由备用模型完成（fallback = true）时加「备用」标签（接口文档第八节；旧数据 model 为 null）。
 */
(function () {
  'use strict';

  var A = window.Admin;
  var fmt = A.fmt;
  var ref = Vue.ref;
  var reactive = Vue.reactive;
  var computed = Vue.computed;
  var watch = Vue.watch;
  var onMounted = Vue.onMounted;

  var IDENTITY_NONE = '__NONE__';

  function pageState(size) {
    return reactive({ list: [], total: 0, page: 1, size: size, loading: false, error: '', loaded: false, seq: 0 });
  }

  A.pages.UserDetail = {
    name: 'PageUserDetail',
    template:
      '<div class="page">' +
      '  <div class="page-toolbar">' +
      '    <el-button :icon="$icons.ArrowLeft" @click="back">返回用户列表</el-button>' +
      '  </div>' +

      '  <pa-error v-if="error && !user" :message="error" @retry="loadUser" />' +
      '  <section v-else-if="!user" class="panel panel--placeholder" v-loading="true"></section>' +
      '  <template v-else>' +

      // ---- 头部：头像、昵称、状态与操作 ----
      '  <section class="panel user-head">' +
      '    <div class="user-head__main">' +
      '      <el-avatar :size="56" :src="user.avatar_url || undefined" class="user-avatar">{{ initial }}</el-avatar>' +
      '      <div class="user-head__info">' +
      '        <div class="user-head__name">' +
      '          <span>{{ fmt.text(user.nickname) }}</span>' +
      '          <el-tag :type="status.type" size="small" disable-transitions>{{ status.text }}</el-tag>' +
      '        </div>' +
      '        <div class="user-head__meta">' +
      '          <span class="mono">{{ fmt.text(user.phone) }}</span><span class="dot">·</span>' +
      '          <span>ID {{ user.id }}</span><span class="dot">·</span>' +
      '          <span>注册于 {{ fmt.time(user.created_at) }}</span>' +
      '        </div>' +
      '      </div>' +
      '    </div>' +
      '    <div class="user-head__actions">' +
      '      <el-button v-if="isDisabled" type="success" plain :icon="$icons.Unlock" @click="enable">启用</el-button>' +
      '      <el-button v-else type="warning" plain :icon="$icons.Lock" @click="disable">停用</el-button>' +
      '      <el-button :icon="$icons.SwitchButton" @click="forceLogout">强制下线</el-button>' +
      '      <el-button :icon="$icons.Edit" @click="openEdit">编辑资料</el-button>' +
      '      <el-button :icon="$icons.Key" @click="resetPassword">重置密码</el-button>' +
      '      <el-button type="danger" plain :icon="$icons.Delete" @click="openDeactivate">注销账号</el-button>' +
      '    </div>' +
      '  </section>' +

      // ---- 基本信息 ----
      '  <section class="panel">' +
      '    <header class="panel__head">' +
      '      <div class="panel__title">基本信息</div>' +
      '      <el-button link :icon="$icons.Refresh" :loading="loadingUser" @click="loadUser">刷新</el-button>' +
      '    </header>' +
      '    <el-descriptions :column="3" border class="info-desc">' +
      '      <el-descriptions-item label="用户 ID">{{ user.id }}</el-descriptions-item>' +
      '      <el-descriptions-item label="手机号"><span class="mono">{{ fmt.text(user.phone) }}</span></el-descriptions-item>' +
      '      <el-descriptions-item label="昵称">{{ fmt.text(user.nickname) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="种植身份">{{ fmt.identity(user.identity) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="主要作物">{{ fmt.crops(user.crops) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="地区">{{ fmt.text(user.region) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="状态"><el-tag :type="status.type" size="small" disable-transitions>{{ status.text }}</el-tag></el-descriptions-item>' +
      '      <el-descriptions-item label="头像">{{ user.avatar_url ? \'已上传\' : \'默认头像\' }}</el-descriptions-item>' +
      '      <el-descriptions-item label="恢复码">' +
      '        <el-tag :type="user.recovery_code_generated ? \'success\' : \'info\'" size="small" disable-transitions>{{ user.recovery_code_generated ? \'已生成\' : \'未生成\' }}</el-tag>' +
      '      </el-descriptions-item>' +
      '      <el-descriptions-item label="注册时间">{{ fmt.time(user.created_at) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="最近登录">{{ fmt.time(user.last_login_at) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="短信验证时间">{{ fmt.time(user.sms_verified_at) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="识别次数">{{ fmt.num(user.recognition_count) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="AI 提问数">{{ fmt.num(user.ai_question_count) }}</el-descriptions-item>' +
      '      <el-descriptions-item label="AI 会话数">{{ fmt.num(user.session_count) }}</el-descriptions-item>' +
      '    </el-descriptions>' +
      '  </section>' +

      // ---- 识别记录 / AI 对话 ----
      '  <section class="panel">' +
      '    <el-tabs v-model="tab" class="detail-tabs">' +
      '      <el-tab-pane :label="recLabel" name="recognitions">' +
      '        <el-table :data="rec.list" v-loading="rec.loading" class="data-table" row-key="id" :row-class-name="recRowClass">' +
      '          <el-table-column label="照片" width="84">' +
      '            <template #default="{ row }">' +
      '              <el-image v-if="row.image_url" :src="row.image_url" :preview-src-list="[row.image_url]" fit="cover"' +
      '                preview-teleported hide-on-click-modal class="rec-thumb">' +
      '                <template #error><div class="rec-thumb__fallback"><el-icon><icon-picture /></el-icon></div></template>' +
      '              </el-image>' +
      '              <div v-else class="rec-thumb rec-thumb__fallback"><el-icon><icon-picture /></el-icon></div>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <el-table-column label="模式" width="104">' +
      '            <template #default="{ row }">{{ modeText(row.mode) }}</template>' +
      '          </el-table-column>' +
      '          <el-table-column label="结果" min-width="170">' +
      '            <template #default="{ row }">' +
      '              <span class="rec-name">{{ fmt.text(row.top_name) }}</span>' +
      '              <el-tag v-if="row.deleted" type="info" size="small" effect="plain" class="ml-6" disable-transitions>用户已删除</el-tag>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <el-table-column label="匹配度" width="88" align="right">' +
      '            <template #default="{ row }"><span class="mono-num">{{ fmt.percent(row.confidence) }}</span></template>' +
      '          </el-table-column>' +
      '          <el-table-column label="严重程度" width="92">' +
      '            <template #default="{ row }">' +
      '              <el-tag v-if="row.severity" :type="severityType(row.severity)" size="small" disable-transitions>{{ row.severity }}</el-tag>' +
      '              <span v-else class="text-muted">—</span>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <el-table-column label="状态" width="108">' +
      '            <template #default="{ row }">' +
      '              <el-tag :type="recStatus(row).type" size="small" effect="plain" disable-transitions>{{ recStatus(row).text }}</el-tag>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <el-table-column label="模型" min-width="170">' +
      '            <template #default="{ row }">' +
      '              <span class="rec-model">' +
      '                <span v-if="row.model" class="mono">{{ row.model }}</span>' +
      '                <span v-else class="text-muted">—</span>' +
      '                <el-tag v-if="row.fallback" :type="fallbackTag.type" size="small" disable-transitions :title="fallbackTag.tip">{{ fallbackTag.text }}</el-tag>' +
      '              </span>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <el-table-column label="时间" width="164">' +
      '            <template #default="{ row }"><span class="mono-num">{{ fmt.time(row.created_at) }}</span></template>' +
      '          </el-table-column>' +
      '          <el-table-column label="操作" width="76" align="center">' +
      '            <template #default="{ row }">' +
      '              <el-button link type="danger" @click="deleteRecognition(row)">删除</el-button>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <template #empty>' +
      '            <pa-error v-if="rec.error" compact :message="rec.error" @retry="loadRecognitions" />' +
      '            <el-empty v-else-if="!rec.loading" :image-size="64" description="暂无识别记录" />' +
      '            <span v-else></span>' +
      '          </template>' +
      '        </el-table>' +
      '        <div class="pager" v-if="rec.total > rec.size">' +
      '          <el-pagination background small layout="total, prev, pager, next" :total="rec.total" :page-size="rec.size"' +
      '            v-model:current-page="rec.page" :disabled="rec.loading" @current-change="loadRecognitions" />' +
      '        </div>' +
      '      </el-tab-pane>' +

      '      <el-tab-pane :label="sesLabel" name="sessions">' +
      '        <el-table :data="ses.list" v-loading="ses.loading" class="data-table" row-key="session_id">' +
      '          <el-table-column label="标题" min-width="260" show-overflow-tooltip>' +
      '            <template #default="{ row }">{{ row.title || \'未命名对话\' }}</template>' +
      '          </el-table-column>' +
      '          <el-table-column label="消息数" width="90" align="right">' +
      '            <template #default="{ row }"><span class="mono-num">{{ fmt.num(row.message_count) }}</span></template>' +
      '          </el-table-column>' +
      '          <el-table-column label="最近更新" width="170">' +
      '            <template #default="{ row }"><span class="mono-num">{{ fmt.time(row.updated_at) }}</span></template>' +
      '          </el-table-column>' +
      '          <el-table-column label="操作" width="120" align="center">' +
      '            <template #default="{ row }">' +
      '              <el-button link type="primary" @click="openSession(row)">查看</el-button>' +
      '              <el-button link type="danger" @click="deleteSession(row)">删除</el-button>' +
      '            </template>' +
      '          </el-table-column>' +
      '          <template #empty>' +
      '            <pa-error v-if="ses.error" compact :message="ses.error" @retry="loadSessions" />' +
      '            <el-empty v-else-if="!ses.loading" :image-size="64" description="暂无 AI 对话" />' +
      '            <span v-else></span>' +
      '          </template>' +
      '        </el-table>' +
      '        <div class="pager" v-if="ses.total > ses.size">' +
      '          <el-pagination background small layout="total, prev, pager, next" :total="ses.total" :page-size="ses.size"' +
      '            v-model:current-page="ses.page" :disabled="ses.loading" @current-change="loadSessions" />' +
      '        </div>' +
      '      </el-tab-pane>' +
      '    </el-tabs>' +
      '  </section>' +
      '  </template>' +

      // ---- 编辑资料 ----
      '  <el-dialog v-model="edit.visible" title="编辑用户资料" width="520px" :close-on-click-modal="false" append-to-body>' +
      '    <el-form label-width="84px" class="dialog-form" @submit.prevent>' +
      '      <el-form-item label="昵称" required :error="edit.nicknameError">' +
      '        <el-input v-model="edit.nickname" maxlength="16" show-word-limit placeholder="1–16 个字" />' +
      '      </el-form-item>' +
      '      <el-form-item label="种植身份">' +
      '        <el-select v-model="edit.identity" class="w-full">' +
      '          <el-option v-for="opt in identityOptions" :key="opt.value" :label="opt.label" :value="opt.value" :disabled="opt.disabled" />' +
      '        </el-select>' +
      '      </el-form-item>' +
      '      <el-form-item label="主要作物">' +
      '        <pa-crops-input v-model="edit.crops" />' +
      '        <div class="form-tip">回车或逗号分隔，最多 10 项，每项最多 20 个字</div>' +
      '      </el-form-item>' +
      '      <el-form-item label="头像">' +
      '        <el-checkbox v-model="edit.clearAvatar" :disabled="!(user && user.avatar_url)">清除头像（改为默认头像）</el-checkbox>' +
      '      </el-form-item>' +
      '    </el-form>' +
      '    <template #footer>' +
      '      <el-button :disabled="edit.saving" @click="edit.visible = false">取消</el-button>' +
      '      <el-button type="primary" :loading="edit.saving" @click="saveEdit">保存</el-button>' +
      '    </template>' +
      '  </el-dialog>' +

      // ---- 临时密码（只显示一次） ----
      '  <el-dialog v-model="temp.visible" title="已重置密码" width="460px" :close-on-click-modal="false" :close-on-press-escape="false"' +
      '    append-to-body @closed="temp.password = \'\'; temp.copied = false">' +
      '    <div ref="tempBox">' +
      '      <el-alert type="warning" show-icon :closable="false" title="临时密码只显示这一次，关闭后无法再次查看"' +
      '        description="该用户已被强制下线。请通过可靠渠道告知用户，并提醒其登录后尽快修改密码。" />' +
      '      <div class="temp-pwd">' +
      '        <code class="temp-pwd__value">{{ temp.password }}</code>' +
      '        <el-button type="primary" :icon="temp.copied ? $icons.Check : $icons.CopyDocument" @click="copyTemp">{{ temp.copied ? \'已复制\' : \'复制\' }}</el-button>' +
      '      </div>' +
      '    </div>' +
      '    <template #footer>' +
      '      <el-button type="primary" plain @click="temp.visible = false">我已记下，关闭</el-button>' +
      '    </template>' +
      '  </el-dialog>' +

      // ---- 注销账号 ----
      '  <el-dialog v-model="deact.visible" title="注销账号" width="500px" :close-on-click-modal="false" append-to-body' +
      '    class="danger-dialog" @closed="deact.phone = \'\'">' +
      '    <template v-if="user">' +
      '      <el-alert type="error" show-icon :closable="false" title="此操作不可恢复" />' +
      '      <p class="danger-text">注销将永久删除该用户的全部数据（不会发送短信通知）：</p>' +
      '      <ul class="danger-list">' +
      '        <li>账号与个人资料（手机号、昵称、头像、身份、作物等）</li>' +
      '        <li>全部识别记录及照片文件</li>' +
      '        <li>全部 AI 对话、消息及聊天图片</li>' +
      '        <li>该用户在所有设备上的登录立即失效</li>' +
      '      </ul>' +
      '      <p class="danger-text">请输入该用户的完整手机号 <strong class="mono">{{ user.phone }}</strong> 以确认：</p>' +
      '      <el-input v-model.trim="deact.phone" maxlength="20" placeholder="完整手机号" @keyup.enter="doDeactivate" />' +
      '    </template>' +
      '    <template #footer>' +
      '      <el-button :disabled="deact.loading" @click="deact.visible = false">取消</el-button>' +
      '      <el-button type="danger" :loading="deact.loading" :disabled="!deactMatches" @click="doDeactivate">永久注销</el-button>' +
      '    </template>' +
      '  </el-dialog>' +

      // ---- AI 对话内容（只读） ----
      '  <el-drawer v-model="chat.visible" :title="chat.title" size="600px" append-to-body class="chat-drawer">' +
      '    <div class="chat-body" v-loading="chat.loading">' +
      '      <pa-error v-if="chat.error" :message="chat.error" @retry="loadMessages" />' +
      '      <el-empty v-else-if="!chat.loading && !chat.messages.length" :image-size="64" description="该对话没有消息" />' +
      '      <div v-else class="chat-list">' +
      '        <div v-for="m in chat.messages" :key="m._key" class="chat-msg" :class="\'is-\' + m._side">' +
      '          <div class="chat-msg__meta">{{ m._role }}<span v-if="m.created_at"> · {{ fmt.time(m.created_at) }}</span></div>' +
      '          <div class="chat-msg__bubble">' +
      '            <div v-if="m._images.length" class="chat-msg__images">' +
      '              <el-image v-for="(url, j) in m._images" :key="url + j" :src="url" :preview-src-list="m._images" :initial-index="j"' +
      '                fit="cover" preview-teleported hide-on-click-modal class="chat-msg__img">' +
      '                <template #error><div class="rec-thumb__fallback"><el-icon><icon-picture /></el-icon></div></template>' +
      '              </el-image>' +
      '            </div>' +
      '            <details v-if="m.reasoning_content" class="chat-reasoning">' +
      '              <summary>思考过程</summary>' +
      '              <div class="chat-text">{{ m.reasoning_content }}</div>' +
      '            </details>' +
      '            <div v-if="m.content" class="chat-text">{{ m.content }}</div>' +
      '            <div v-else-if="!m._images.length" class="chat-text text-muted">（空消息）</div>' +
      '          </div>' +
      '          <div v-if="m._side === \'assistant\' && m.model" class="chat-msg__model">' +
      '            <span>模型：<span class="mono">{{ m.model }}</span></span>' +
      '            <el-tag v-if="m.fallback" :type="fallbackTag.type" size="small" disable-transitions :title="fallbackTag.tip">{{ fallbackTag.text }}</el-tag>' +
      '          </div>' +
      '        </div>' +
      '      </div>' +
      '    </div>' +
      '  </el-drawer>' +
      '</div>',
    setup: function () {
      var id = A.router.current.params.id;
      var user = ref(null);
      var error = ref('');
      var loadingUser = ref(false);
      var tab = ref('recognitions');
      var tempBox = ref(null);

      var rec = pageState(10);
      var ses = pageState(10);

      var edit = reactive({ visible: false, saving: false, nickname: '', identity: IDENTITY_NONE, crops: [], clearAvatar: false, nicknameError: '' });
      var temp = reactive({ visible: false, password: '', copied: false });
      var deact = reactive({ visible: false, phone: '', loading: false });
      var chat = reactive({ visible: false, loading: false, error: '', title: '', sessionId: null, messages: [], seq: 0 });

      var identityOptions = computed(function () {
        return A.labels.identities.map(function (v) { return { value: v, label: v, disabled: false }; })
          .concat([{ value: IDENTITY_NONE, label: '未设置' }]);
      });

      var userPath = 'users/' + A.enc(id);

      // ---------- 用户 ----------
      function loadUser() {
        loadingUser.value = true;
        return A.api.get(userPath, null, { silent: !!user.value ? false : true }).then(function (d) {
          user.value = d;
          error.value = '';
        }, function (err) {
          if (!user.value) error.value = err.message;
        }).then(function () { loadingUser.value = false; });
      }

      var status = computed(function () {
        var s = user.value && user.value.status;
        return A.labels.userStatus[s] || { text: fmt.text(s), type: 'info' };
      });
      var isDisabled = computed(function () { return !!user.value && user.value.status === 'DISABLED'; });
      var initial = computed(function () {
        var n = ((user.value && user.value.nickname) || '').trim();
        return n ? n.charAt(0) : '用';
      });

      function back() {
        A.router.push('/users');
      }

      function disable() {
        A.confirmAction({
          title: '停用账号',
          message: '停用后该用户会被立即强制下线，并且无法再登录 App，直到重新启用。可填写停用原因（选填）：',
          confirmText: '停用',
          danger: true,
          prompt: {
            placeholder: '停用原因（选填，最多 200 字）',
            validator: function (v) { return !v || v.length <= 200 || '停用原因最多 200 字'; }
          },
          action: function (reason) {
            var r = (reason || '').trim();
            return A.api.post(userPath + '/disable', { reason: r || null });
          }
        }).then(function (ok) {
          if (!ok) return;
          A.message.success('已停用，该用户已被强制下线');
          loadUser();
        });
      }

      function enable() {
        A.confirmAction({
          title: '启用账号',
          message: '启用后该用户可以重新登录 App。确定启用吗？',
          type: 'info',
          confirmText: '启用',
          action: function () { return A.api.post(userPath + '/enable'); }
        }).then(function (ok) {
          if (!ok) return;
          A.message.success('已启用');
          loadUser();
        });
      }

      function forceLogout() {
        A.confirmAction({
          title: '强制下线',
          message: '该用户在所有设备上的登录将立即失效，需要重新登录。确定继续吗？',
          confirmText: '强制下线',
          action: function () { return A.api.post(userPath + '/logout'); }
        }).then(function (ok) {
          if (ok) A.message.success('已强制下线');
        });
      }

      function openEdit() {
        var u = user.value;
        if (!u) return;
        edit.nickname = u.nickname || '';
        edit.identity = A.labels.identities.indexOf(u.identity) >= 0 ? u.identity : IDENTITY_NONE;
        edit.crops = A.splitCrops(u.crops).slice(0, 10);
        edit.clearAvatar = false;
        edit.nicknameError = '';
        edit.visible = true;
      }

      function saveEdit() {
        if (edit.saving) return;
        var nickname = (edit.nickname || '').trim();
        if (!nickname) {
          edit.nicknameError = '请填写昵称';
          return;
        }
        if (nickname.length > 16) {
          edit.nicknameError = '昵称最多 16 个字';
          return;
        }
        edit.nicknameError = '';
        edit.saving = true;
        A.api.put(userPath + '/profile', {
          nickname: nickname,
          identity: edit.identity === IDENTITY_NONE ? '' : edit.identity, // 空串表示清除身份
          crops: edit.crops.join(','),
          clear_avatar: !!edit.clearAvatar
        }).then(function (d) {
          if (d) user.value = d;
          else loadUser();
          edit.visible = false;
          A.message.success('资料已保存');
        }, function () { /* 错误已提示 */ }).then(function () { edit.saving = false; });
      }

      function resetPassword() {
        A.confirmAction({
          title: '重置密码',
          message: '将为该用户生成一个 12 位临时密码，并强制其所有设备下线。临时密码只显示一次。确定重置吗？',
          confirmText: '重置密码',
          action: function () {
            return A.api.post(userPath + '/reset-password').then(function (d) {
              temp.password = (d && d.temp_password) || '';
              temp.copied = false;
            });
          }
        }).then(function (ok) {
          if (ok && temp.password) temp.visible = true;
        });
      }

      function copyTemp() {
        A.copyText(temp.password, tempBox.value).then(function (ok) {
          if (ok) {
            temp.copied = true;
            A.message.success('已复制到剪贴板');
          } else {
            A.message.warning('复制失败，请手动选中密码复制');
          }
        });
      }

      function openDeactivate() {
        deact.phone = '';
        deact.visible = true;
      }

      var deactMatches = computed(function () {
        return !!user.value && !!user.value.phone && deact.phone === String(user.value.phone);
      });

      function doDeactivate() {
        if (!deactMatches.value || deact.loading) return;
        deact.loading = true;
        A.api.post(userPath + '/deactivate', { confirm_phone: deact.phone }).then(function () {
          deact.visible = false;
          A.message.success('账号已注销，数据已删除');
          A.router.push('/users');
        }, function () { /* 错误已提示 */ }).then(function () { deact.loading = false; });
      }

      // ---------- 识别记录 ----------
      function loadPage(state, path) {
        var mySeq = ++state.seq;
        state.loading = true;
        state.error = '';
        return A.api.get(path, { page: state.page, size: state.size }, { silent: true }).then(function (d) {
          if (mySeq !== state.seq) return;
          state.list = (d && d.list) || [];
          state.total = Number(d && d.total) || 0;
          state.loaded = true;
          var lastPage = Math.max(1, Math.ceil(state.total / state.size));
          if (!state.list.length && state.page > lastPage) {
            state.page = lastPage;
            loadPage(state, path);
          }
        }, function (err) {
          if (mySeq !== state.seq) return;
          state.list = [];
          state.error = err.message;
        }).then(function () {
          if (mySeq === state.seq) state.loading = false;
        });
      }

      function loadRecognitions() {
        return loadPage(rec, userPath + '/recognitions');
      }

      function loadSessions() {
        return loadPage(ses, userPath + '/sessions');
      }

      function recStatus(row) {
        return A.labels.recStatus[row.status] || { text: fmt.text(row.status), type: 'info' };
      }

      function severityType(s) {
        return A.labels.severity[s] || 'info';
      }

      function recRowClass(scope) {
        return scope.row && scope.row.deleted ? 'is-soft-deleted' : '';
      }

      function deleteRecognition(row) {
        A.confirmAction({
          title: '删除识别记录',
          message: '将彻底删除这条识别记录及其照片文件，无法恢复。确定删除吗？',
          confirmText: '删除',
          danger: true,
          action: function () { return A.api.del('recognitions/' + A.enc(row.id)); }
        }).then(function (ok) {
          if (!ok) return;
          A.message.success('已删除');
          loadRecognitions();
          loadUser();
        });
      }

      // ---------- AI 对话 ----------
      function normalizeMessages(list) {
        return (Array.isArray(list) ? list : []).map(function (m, i) {
          var images = Array.isArray(m.image_urls) && m.image_urls.length ? m.image_urls : (m.image_url ? [m.image_url] : []);
          var role = m.role || '';
          return Object.assign({}, m, {
            _key: i,
            _images: images.filter(function (u) { return !!u; }),
            _side: role === 'user' ? 'user' : (role === 'assistant' ? 'assistant' : 'system'),
            _role: role === 'user' ? '用户' : (role === 'assistant' ? 'AI 助手' : (role === 'system' ? '系统' : role))
          });
        });
      }

      function openSession(row) {
        chat.sessionId = row.session_id;
        chat.title = row.title || '未命名对话';
        chat.messages = [];
        chat.visible = true;
        loadMessages();
      }

      function loadMessages() {
        var mySeq = ++chat.seq;
        chat.loading = true;
        chat.error = '';
        A.api.get('sessions/' + A.enc(chat.sessionId) + '/messages', null, { silent: true }).then(function (d) {
          if (mySeq !== chat.seq) return;
          chat.messages = normalizeMessages(d);
        }, function (err) {
          if (mySeq !== chat.seq) return;
          chat.error = err.message;
        }).then(function () {
          if (mySeq === chat.seq) chat.loading = false;
        });
      }

      function deleteSession(row) {
        A.confirmAction({
          title: '删除 AI 对话',
          message: '将彻底删除对话「' + (row.title || '未命名对话') + '」及其全部消息和聊天图片，无法恢复。确定删除吗？',
          confirmText: '删除',
          danger: true,
          action: function () { return A.api.del('sessions/' + A.enc(row.session_id)); }
        }).then(function (ok) {
          if (!ok) return;
          A.message.success('已删除');
          if (chat.visible && chat.sessionId === row.session_id) chat.visible = false;
          loadSessions();
          loadUser();
        });
      }

      watch(tab, function (t) {
        if (t === 'sessions' && !ses.loaded && !ses.loading) loadSessions();
        if (t === 'recognitions' && !rec.loaded && !rec.loading) loadRecognitions();
      });

      var recLabel = computed(function () {
        return rec.loaded ? '识别记录（' + fmt.num(rec.total) + '）' : '识别记录';
      });
      var sesLabel = computed(function () {
        return ses.loaded ? 'AI 对话（' + fmt.num(ses.total) + '）' : 'AI 对话';
      });

      onMounted(function () {
        loadUser();
        loadRecognitions();
      });

      return {
        fmt: fmt, user: user, error: error, loadingUser: loadingUser, tab: tab, tempBox: tempBox,
        rec: rec, ses: ses, edit: edit, temp: temp, deact: deact, chat: chat,
        identityOptions: identityOptions, status: status, isDisabled: isDisabled, initial: initial,
        recLabel: recLabel, sesLabel: sesLabel, deactMatches: deactMatches,
        modeText: A.modeText, recStatus: recStatus, severityType: severityType, recRowClass: recRowClass,
        fallbackTag: A.labels.fallback,
        back: back, loadUser: loadUser, disable: disable, enable: enable, forceLogout: forceLogout,
        openEdit: openEdit, saveEdit: saveEdit, resetPassword: resetPassword, copyTemp: copyTemp,
        openDeactivate: openDeactivate, doDeactivate: doDeactivate,
        loadRecognitions: loadRecognitions, deleteRecognition: deleteRecognition,
        loadSessions: loadSessions, openSession: openSession, loadMessages: loadMessages, deleteSession: deleteSession
      };
    }
  };
})();
