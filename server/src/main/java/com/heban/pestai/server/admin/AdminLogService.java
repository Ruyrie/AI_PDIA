package com.heban.pestai.server.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.heban.pestai.server.admin.entity.AdminLog;
import com.heban.pestai.server.admin.mapper.AdminLogMapper;
import com.heban.pestai.server.admin.vo.AdminLogVO;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.vo.PageVO;

/**
 * 后台操作日志（t_admin_log）：登录成功 / 失败、退出与全部写操作。调用方保证 detail 中不出现密钥明文与用户密码。
 * <ul>
 *   <li>在事务中调用时，登记到事务提交之后再写（操作回滚就不留日志）；没有事务时立即写；</li>
 *   <li>写日志用独立的新事务（REQUIRES_NEW），失败只记服务器日志，不影响操作本身。</li>
 * </ul>
 */
@Service
public class AdminLogService {

    private static final Logger log = LoggerFactory.getLogger(AdminLogService.class);

    private final AdminLogMapper mapper;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public AdminLogService(AdminLogMapper mapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.mapper = mapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 当前管理员（AdminContext）的操作 */
    public void log(String action, String target, String detail) {
        AdminContext.Principal principal = AdminContext.require();
        record(principal.id(), principal.username(), action, target, detail, principal.ip());
    }

    /** 指定管理员（登录 / 登录失败时还没有 AdminContext） */
    public void record(Long adminId, String adminName, String action, String target, String detail, String ip) {
        AdminLog entry = new AdminLog();
        entry.setAdminId(adminId);
        entry.setAdminName(Rules.truncate(adminName == null || adminName.isBlank() ? "-" : adminName, 32));
        entry.setAction(action);
        entry.setTarget(Rules.truncate(target, 128));
        entry.setDetail(Rules.truncate(detail, 500));
        entry.setIp(Rules.truncate(ip, 64));
        entry.setCreatedAt(LocalDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(entry);
                }
            });
        } else {
            write(entry);
        }
    }

    private void write(AdminLog entry) {
        try {
            requiresNew.executeWithoutResult(status -> mapper.insert(entry));
        } catch (RuntimeException e) {
            log.warn("写入后台操作日志失败：{} {}：{}", entry.getAction(), entry.getTarget(), e.getMessage());
        }
    }

    /** GET admin/api/logs：按时间倒序分页；action 为空时不过滤 */
    public PageVO<AdminLogVO> page(String action, int page, int size) {
        String filter = action == null || action.isBlank() ? null : action.trim();
        long total = mapper.count(filter);
        long offset = (long) (page - 1) * size;
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<AdminLogVO> list = mapper.selectPage(filter, offset, size).stream()
                .map(l -> new AdminLogVO(l.getId(), l.getAdminName(), l.getAction(), l.getTarget(), l.getDetail(),
                        l.getIp(), l.getCreatedAt()))
                .toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }
}
