package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminLogMapper;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.mapper.SystemConfigMapper;
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 在真实的内嵌 Tomcat（随机端口）上验证 application.yml 的会话 Cookie 配置：名称、Path=/admin、HttpOnly、SameSite=Strict；
 * 登录后换了新的会话 ID；App 接口不创建会话。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/pestai_unit_test_unreachable",
        "spring.datasource.username=nobody",
        "spring.datasource.password=",
        "vision.api-key=",
        "chat.api-key=",
        "sms.provider=log",
        "pestai.file-root=target/test-files",
        "pestai.jwt.secret=integration-test-secret-0123456789abcdef"
})
class AdminSessionCookieTest {

    private static final String PASSWORD = "Initial-Pass-2026";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AdminMapper adminMapper;
    @MockBean
    private AdminLogMapper adminLogMapper;
    @MockBean
    private SystemConfigMapper systemConfigMapper;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private PlatformTransactionManager transactionManager;

    @Test
    void sessionCookieIsHttpOnlySameSiteStrictAndScopedToAdmin() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(false);
        when(adminMapper.selectByUsername("admin")).thenReturn(admin);
        when(adminMapper.selectById(1L)).thenReturn(admin);

        // 先访问一次 me（未登录）不会创建会话；登录时创建新会话
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        json.add("X-Real-IP", "192.0.2.50");
        ResponseEntity<String> login = rest.exchange("/admin/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}", json), String.class);
        assertThat(login.getBody()).contains("\"code\":0").contains("csrf_token");
        List<String> cookies = login.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(1);
        String cookie = cookies.get(0);
        assertThat(cookie).startsWith("PESTAI_ADMIN_SESSION=").contains("Path=/admin").contains("HttpOnly")
                .contains("SameSite=Strict");

        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));
        ResponseEntity<String> me = rest.exchange("/admin/api/auth/me", HttpMethod.GET, new HttpEntity<>(withCookie),
                String.class);
        assertThat(me.getBody()).contains("\"code\":0").contains("\"username\":\"admin\"");

        // 再次登录（带着旧会话）：会话 ID 改变，防会话固定
        HttpHeaders again = new HttpHeaders();
        again.addAll(json);
        again.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));
        ResponseEntity<String> second = rest.exchange("/admin/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}", again), String.class);
        String newCookie = second.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.substring(0, newCookie.indexOf(';'))).isNotEqualTo(cookie.substring(0, cookie.indexOf(';')));

        // App 接口无状态：不下发会话 Cookie
        ResponseEntity<String> app = rest.getForEntity("/pestai/v1/recognitions/recent", String.class);
        assertThat(app.getBody()).contains("40101");
        assertThat(app.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }
}
