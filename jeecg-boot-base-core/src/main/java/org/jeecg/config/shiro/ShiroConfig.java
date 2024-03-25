package org.jeecg.config.shiro;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.crazycake.shiro.*;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.JeecgBaseConfig;
import org.jeecg.config.shiro.filters.CustomShiroFilterFactoryBean;
import org.jeecg.config.shiro.filters.JwtFilter;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.DelegatingFilterProxy;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import javax.annotation.Resource;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import java.lang.reflect.Method;
import java.util.*;

/**
 * @author: Scott
 * @date: 2018/2/7
 * @description: shiro 配置类
 */

@Slf4j
@Configuration
// 免认证注解 @IgnoreAuth 注解生效范围配置
@ComponentScan(basePackages = {"org.jeecg"})
public class ShiroConfig {

    @Resource
    private LettuceConnectionFactory lettuceConnectionFactory;
    @Autowired
    private Environment env;
    @Resource
    private JeecgBaseConfig jeecgBaseConfig;
    @Autowired(required = false)
    private RedisProperties redisProperties;
    /**
     * Filter Chain定义说明
     *
     * 1、一个URL可以配置多个Filter，使用逗号分隔
     * 2、当设置多个过滤器时，全部验证通过，才视为通过
     * 3、部分过滤器可指定参数，如perms，roles
     */
    @Bean("shiroFilterFactoryBean")
    public ShiroFilterFactoryBean shiroFilter(SecurityManager securityManager) {
        CustomShiroFilterFactoryBean shiroFilterFactoryBean = new CustomShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        // 拦截器
        Map<String, String> filterChainDefinitionMap = new LinkedHashMap<String, String>();

        //支持yml方式，配置拦截排除
        if(jeecgBaseConfig!=null && jeecgBaseConfig.getShiro()!=null){
            String shiroExcludeUrls = jeecgBaseConfig.getShiro().getExcludeUrls();
            if(oConvertUtils.isNotEmpty(shiroExcludeUrls)){
                String[] permissionUrl = shiroExcludeUrls.split(",");
                for(String url : permissionUrl){
                    filterChainDefinitionMap.put(url,"anon");
                }
            }
        }

        // 配置不会被拦截的链接 顺序判断
        filterChainDefinitionMap.put("/sys/cas/client/validateLogin", "anon"); //cas验证登录
        filterChainDefinitionMap.put("/sys/randomImage/**", "anon"); //登录验证码接口排除
        filterChainDefinitionMap.put("/sys/checkCaptcha", "anon"); //登录验证码接口排除
        filterChainDefinitionMap.put("/sys/login", "anon"); //登录接口排除
        filterChainDefinitionMap.put("/sys/mLogin", "anon"); //登录接口排除
        filterChainDefinitionMap.put("/sys/logout", "anon"); //登出接口排除
        filterChainDefinitionMap.put("/sys/thirdLogin/**", "anon"); //第三方登录
        filterChainDefinitionMap.put("/sys/getEncryptedString", "anon"); //获取加密串
        filterChainDefinitionMap.put("/sys/sms", "anon");//短信验证码
        filterChainDefinitionMap.put("/sys/phoneLogin", "anon");//手机登录
        filterChainDefinitionMap.put("/sys/user/checkOnlyUser", "anon");//校验用户是否存在
        filterChainDefinitionMap.put("/sys/user/register", "anon");//用户注册
        filterChainDefinitionMap.put("/sys/user/phoneVerification", "anon");//用户忘记密码验证手机号
        filterChainDefinitionMap.put("/sys/user/passwordChange", "anon");//用户更改密码
        filterChainDefinitionMap.put("/auth/2step-code", "anon");//登录验证码
        filterChainDefinitionMap.put("/sys/common/static/**", "anon");//图片预览 &下载文件不限制token
        filterChainDefinitionMap.put("/sys/common/pdf/**", "anon");//pdf预览

        //filterChainDefinitionMap.put("/sys/common/view/**", "anon");//图片预览不限制token
        //filterChainDefinitionMap.put("/sys/common/download/**", "anon");//文件下载不限制token
        filterChainDefinitionMap.put("/generic/**", "anon");//pdf预览需要文件

        filterChainDefinitionMap.put("/sys/getLoginQrcode/**", "anon"); //登录二维码
        filterChainDefinitionMap.put("/sys/getQrcodeToken/**", "anon"); //监听扫码
        filterChainDefinitionMap.put("/sys/checkAuth", "anon"); //授权接口排除


        //update-begin--Author:scott Date:20221116 for：排除静态资源后缀
        filterChainDefinitionMap.put("/", "anon");
        filterChainDefinitionMap.put("/doc.html", "anon");
        filterChainDefinitionMap.put("/**/*.js", "anon");
        filterChainDefinitionMap.put("/**/*.css", "anon");
        filterChainDefinitionMap.put("/**/*.html", "anon");
        filterChainDefinitionMap.put("/**/*.svg", "anon");
        filterChainDefinitionMap.put("/**/*.pdf", "anon");
        filterChainDefinitionMap.put("/**/*.jpg", "anon");
        filterChainDefinitionMap.put("/**/*.png", "anon");
        filterChainDefinitionMap.put("/**/*.gif", "anon");
        filterChainDefinitionMap.put("/**/*.ico", "anon");
        filterChainDefinitionMap.put("/**/*.ttf", "anon");
        filterChainDefinitionMap.put("/**/*.woff", "anon");
        filterChainDefinitionMap.put("/**/*.woff2", "anon");
        //update-end--Author:scott Date:20221116 for：排除静态资源后缀

        filterChainDefinitionMap.put("/druid/**", "anon");
        filterChainDefinitionMap.put("/swagger-ui.html", "anon");
        filterChainDefinitionMap.put("/swagger**/**", "anon");
        filterChainDefinitionMap.put("/webjars/**", "anon");
        filterChainDefinitionMap.put("/v2/**", "anon");

        // update-begin--Author:sunjianlei Date:20210510 for：排除消息通告查看详情页面（用于第三方APP）
        filterChainDefinitionMap.put("/sys/annountCement/show/**", "anon");
        // update-end--Author:sunjianlei Date:20210510 for：排除消息通告查看详情页面（用于第三方APP）

        //积木报表排除
        filterChainDefinitionMap.put("/jmreport/**", "anon");
        filterChainDefinitionMap.put("/**/*.js.map", "anon");
        filterChainDefinitionMap.put("/**/*.css.map", "anon");
        
        //拖拽仪表盘设计器排除
        filterChainDefinitionMap.put("/drag/view", "anon");
        filterChainDefinitionMap.put("/drag/page/queryById", "anon");
        filterChainDefinitionMap.put("/drag/onlDragDatasetHead/getAllChartData", "anon");
        filterChainDefinitionMap.put("/drag/onlDragDatasetHead/getTotalData", "anon");
        filterChainDefinitionMap.put("/drag/mock/json/**", "anon");
        //大屏模板例子
        filterChainDefinitionMap.put("/test/bigScreen/**", "anon");
        filterChainDefinitionMap.put("/bigscreen/template1/**", "anon");
        filterChainDefinitionMap.put("/bigscreen/template1/**", "anon");
        //filterChainDefinitionMap.put("/test/jeecgDemo/rabbitMqClientTest/**", "anon"); //MQ测试
        //filterChainDefinitionMap.put("/test/jeecgDemo/html", "anon"); //模板页面
        //filterChainDefinitionMap.put("/test/jeecgDemo/redis/**", "anon"); //redis测试

        //websocket排除
        filterChainDefinitionMap.put("/websocket/**", "anon");//系统通知和公告
        filterChainDefinitionMap.put("/newsWebsocket/**", "anon");//CMS模块
        filterChainDefinitionMap.put("/vxeSocket/**", "anon");//JVxeTable无痕刷新示例

        //性能监控——安全隐患泄露TOEKN（durid连接池也有）
        //filterChainDefinitionMap.put("/actuator/**", "anon");
        //测试模块排除
        filterChainDefinitionMap.put("/test/seata/**", "anon");

        //错误路径排除
        filterChainDefinitionMap.put("/error", "anon");
        // 企业微信证书排除
        filterChainDefinitionMap.put("/WW_verify*", "anon");


        // 通过注解免登录url
        List<String> ignoreAuthUrlList = collectIgnoreAuthUrl();
        if (!CollectionUtils.isEmpty(ignoreAuthUrlList)) {
            for (String url : ignoreAuthUrlList) {
                filterChainDefinitionMap.put(url, "anon");
            }
        }


        // 添加自己的过滤器并且取名为jwt
        Map<String, Filter> filterMap = new HashMap<String, Filter>(1);
        //如果cloudServer为空 则说明是单体 需要加载跨域配置【微服务跨域切换】
        Object cloudServer = env.getProperty(CommonConstant.CLOUD_SERVER_KEY);
        filterMap.put("jwt", new JwtFilter(cloudServer==null));
        shiroFilterFactoryBean.setFilters(filterMap);
        // <!-- 过滤链定义，从上向下顺序执行，一般将/**放在最为下边
        filterChainDefinitionMap.put("/**", "jwt");

        // 未授权界面返回JSON
        shiroFilterFactoryBean.setUnauthorizedUrl("/sys/common/403");
        shiroFilterFactoryBean.setLoginUrl("/sys/common/403");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterChainDefinitionMap);
        return shiroFilterFactoryBean;
    }

    //update-begin---author:chenrui ---date:20240126  for：【QQYUN-7932】AI助手------------
    @Bean
    public FilterRegistrationBean shiroFilterRegistration() {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new DelegatingFilterProxy("shiroFilterFactoryBean"));
        registration.setEnabled(true);
        registration.addUrlPatterns("/*");
        //支持异步
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }
    //update-end---author:chenrui ---date:20240126  for：【QQYUN-7932】AI助手------------

    @Bean("securityManager")
    public DefaultWebSecurityManager securityManager(ShiroRealm myRealm) {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(myRealm);

        /*
         * 关闭shiro自带的session，详情见文档
         * http://shiro.apache.org/session-management.html#SessionManagement-
         * StatelessApplications%28Sessionless%29
         */
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        DefaultSessionStorageEvaluator defaultSessionStorageEvaluator = new DefaultSessionStorageEvaluator();
        defaultSessionStorageEvaluator.setSessionStorageEnabled(false);
        subjectDAO.setSessionStorageEvaluator(defaultSessionStorageEvaluator);
        securityManager.setSubjectDAO(subjectDAO);
        //自定义缓存实现,使用redis
        securityManager.setCacheManager(redisCacheManager());
        return securityManager;
    }

    /**
     * 下面的代码是添加注解支持
     * @return
     */
    @Bean
    @DependsOn("lifecycleBeanPostProcessor")
    public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        defaultAdvisorAutoProxyCreator.setProxyTargetClass(true);
        /**
         * 解决重复代理问题 github#994
         * 添加前缀判断 不匹配 任何Advisor
         */
        defaultAdvisorAutoProxyCreator.setUsePrefix(true);
        defaultAdvisorAutoProxyCreator.setAdvisorBeanNamePrefix("_no_advisor");
        return defaultAdvisorAutoProxyCreator;
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(DefaultWebSecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }

    /**
     * cacheManager 缓存 redis实现
     * 使用的是shiro-redis开源插件
     *
     * @return
     */
    public RedisCacheManager redisCacheManager() {
        log.info("===============(1)创建缓存管理器RedisCacheManager");
        RedisCacheManager redisCacheManager = new RedisCacheManager();
        redisCacheManager.setRedisManager(redisManager());
        //redis中针对不同用户缓存(此处的id需要对应user实体中的id字段,用于唯一标识)
        redisCacheManager.setPrincipalIdFieldName("id");
        //用户权限信息缓存时间
        redisCacheManager.setExpire(200000);
        return redisCacheManager;
    }

    /**
     * 配置shiro redisManager
     * 使用的是shiro-redis开源插件
     *
     * @return
     */
    @Bean
    public IRedisManager redisManager() {
        log.info("===============(2)创建RedisManager,连接Redis..");
        IRedisManager manager;
        // sentinel cluster redis（【issues/5569】shiro集成 redis 不支持 sentinel 方式部署的redis集群 #5569）
        if (Objects.nonNull(redisProperties)
                && Objects.nonNull(redisProperties.getSentinel())
                && !CollectionUtils.isEmpty(redisProperties.getSentinel().getNodes())) {
            RedisSentinelManager sentinelManager = new RedisSentinelManager();
            sentinelManager.setMasterName(redisProperties.getSentinel().getMaster());
            sentinelManager.setHost(String.join(",", redisProperties.getSentinel().getNodes()));
            sentinelManager.setPassword(redisProperties.getSentinel().getPassword());
            sentinelManager.setDatabase(redisProperties.getDatabase());

            return sentinelManager;
        }
        
        // redis 单机支持，在集群为空，或者集群无机器时候使用 add by jzyadmin@163.com
        if (lettuceConnectionFactory.getClusterConfiguration() == null || lettuceConnectionFactory.getClusterConfiguration().getClusterNodes().isEmpty()) {
            RedisManager redisManager = new RedisManager();
            redisManager.setHost(lettuceConnectionFactory.getHostName() + ":" + lettuceConnectionFactory.getPort());
            //(lettuceConnectionFactory.getPort());
            redisManager.setDatabase(lettuceConnectionFactory.getDatabase());
            redisManager.setTimeout(0);
            if (!StringUtils.isEmpty(lettuceConnectionFactory.getPassword())) {
                redisManager.setPassword(lettuceConnectionFactory.getPassword());
            }
            manager = redisManager;
        }else{
            // redis集群支持，优先使用集群配置
            RedisClusterManager redisManager = new RedisClusterManager();
            Set<HostAndPort> portSet = new HashSet<>();
            lettuceConnectionFactory.getClusterConfiguration().getClusterNodes().forEach(node -> portSet.add(new HostAndPort(node.getHost() , node.getPort())));
            //update-begin--Author:scott Date:20210531 for：修改集群模式下未设置redis密码的bug issues/I3QNIC
            if (oConvertUtils.isNotEmpty(lettuceConnectionFactory.getPassword())) {
                JedisCluster jedisCluster = new JedisCluster(portSet, 2000, 2000, 5,
                    lettuceConnectionFactory.getPassword(), new GenericObjectPoolConfig());
                redisManager.setPassword(lettuceConnectionFactory.getPassword());
                redisManager.setJedisCluster(jedisCluster);
            } else {
                JedisCluster jedisCluster = new JedisCluster(portSet);
                redisManager.setJedisCluster(jedisCluster);
            }
            //update-end--Author:scott Date:20210531 for：修改集群模式下未设置redis密码的bug issues/I3QNIC
            manager = redisManager;
        }
        return manager;
    }


    @SneakyThrows
    public List<String> collectIgnoreAuthUrl() {
        List<String> ignoreAuthUrls = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        // 获取当前类的扫描注解的配置
        Set<BeanDefinition> components = new HashSet<>();
        for (String basePackage : AnnotationUtils.getAnnotation(ShiroConfig.class, ComponentScan.class).basePackages()) {
            components.addAll(provider.findCandidateComponents(basePackage));
        }

        // 逐个匹配获取免认证路径
        for (BeanDefinition component : components) {
            String beanClassName = component.getBeanClassName();
            Class<?> clazz = Class.forName(beanClassName);
            RequestMapping base = clazz.getAnnotation(RequestMapping.class);
            String[] baseUrl = {};
            if (Objects.nonNull(base)) {
                baseUrl = base.value();
            }
            Method[] methods = clazz.getDeclaredMethods();

            for (Method method : methods) {
                if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(RequestMapping.class)) {
                    RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                } else if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(GetMapping.class)) {
                    GetMapping requestMapping = method.getAnnotation(GetMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                } else if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(PostMapping.class)) {
                    PostMapping requestMapping = method.getAnnotation(PostMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                } else if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(PutMapping.class)) {
                    PutMapping requestMapping = method.getAnnotation(PutMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                } else if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(DeleteMapping.class)) {
                    DeleteMapping requestMapping = method.getAnnotation(DeleteMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                } else if (method.isAnnotationPresent(IgnoreAuth.class) && method.isAnnotationPresent(PatchMapping.class)) {
                    PatchMapping requestMapping = method.getAnnotation(PatchMapping.class);
                    String[] uri = requestMapping.value();
                    ignoreAuthUrls.addAll(rebuildUrl(baseUrl, uri));
                }
            }
        }

        return ignoreAuthUrls;
    }

    private List<String> rebuildUrl(String[] bases, String[] uris) {
        List<String> urls = new ArrayList<>();
        for (String base : bases) {
            for (String uri : uris) {
                urls.add(prefix(base)+prefix(uri));
            }
        }
        return urls;
    }

    private String prefix(String seg) {
        return seg.startsWith("/") ? seg : "/"+seg;
    }

}
