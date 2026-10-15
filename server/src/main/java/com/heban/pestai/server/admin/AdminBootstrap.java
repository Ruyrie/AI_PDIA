package com.heban.pestai.server.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.config.PestaiProperties;

/**
 * 启动时创建初始管理员：t_admin 为空时创建 admin，密码取环境变量 PESTAI_ADMIN_INITIAL_PASSWORD
 * （部署脚本首次安装时随机生成并在部署结果中显示）；未设置时随机生成 16 位密码，并在启动日志中以 WARN 打印这一次。
 * must_change_password = 1，首次登录后必须修改。
 * <p>
 * 读取 t_admin 失败（还没有执行迁移 006、数据库不可用）只记录错误，不影响启动 —— App 接口照常可用。
 */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    public static final String INITIAL_USERNAME = "admin";

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;
    private final PestaiProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminBootstrap(AdminMapper adminMapper, PasswordEncoder passwordEncoder, PestaiProperties properties,
                          Clock clock) {
        this.adminMapper = adminMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        createInitialAdminIfAbsent();
    }

    /** @return 是否创建了管理员 */
    boolean createInitialAdminIfAbsent() {
        try {
            if (adminMapper.countAll() > 0) {
                return false;
            }
        } catch (RuntimeException e) {
            log.error("读取管理员表 t_admin 失败（是否已执行 sql/migrations/006_admin.sql？）：后台管理暂不可用，App 接口不受影响。{}",
                    e.getMessage());
            return false;
        }
        String configured = properties.getAdmin().getInitialPassword();
        boolean generated = configured == null || configured.isBlank();
        String password = generated ? RandomPasswords.generate(random, 16) : configured.trim();

        LocalDateTime now = LocalDateTime.now(clock);
        Admin admin = new Admin();
        admin.setUsername(INITIAL_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setMustChangePassword(true);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        try {
            adminMapper.insert(admin);
        } catch (DuplicateKeyException e) {
            return false;
        } catch (RuntimeException e) {
            log.error("创建初始管理员失败：{}", e.getMessage());
            return false;
        }
        if (generated) {
            log.warn("已创建后台管理员 {}，初始密码：{}（未设置 PESTAI_ADMIN_INITIAL_PASSWORD，随机生成，只在这次启动日志中显示；"
                    + "登录 /admin/ 后必须修改）", INITIAL_USERNAME, password);
        } else {
            log.warn("已创建后台管理员 {}，初始密码为环境变量 PESTAI_ADMIN_INITIAL_PASSWORD 的值（登录 /admin/ 后必须修改）",
                    INITIAL_USERNAME);
        }
        return true;
    }
}
