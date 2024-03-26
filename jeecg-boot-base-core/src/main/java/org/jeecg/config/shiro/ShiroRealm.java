package org.jeecg.config.shiro;

import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.jeecg.common.api.CommonAPI;
import org.jeecg.common.config.TenantContext;
import org.jeecg.common.constant.CacheConstant;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.common.util.TokenUtils;
import org.jeecg.common.util.oConvertUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * @Description: 用户登录鉴权和获取用户授权
 * @Author: Scott
 * @Date: 2019-4-23 8:13
 * @Version: 1.1
 */
@Component
@Slf4j
public class ShiroRealm extends AuthorizingRealm {
	@Lazy
    @Resource
    private CommonAPI commonApi;

    @Lazy
    @Resource
    private RedisUtil redisUtil;

    /**
     * 必须重写此方法，不然Shiro会报错
     */
    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    /**
     * 权限信息认证(包括角色以及权限)是用户访问controller的时候才进行验证(redis存储的此处权限信息)
     * 触发检测用户权限时才会调用此方法，例如checkRole,checkPermission
     *
     * @param principals 身份信息
     * @return AuthorizationInfo 权限信息
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        log.debug("===============Shiro权限认证开始============ [ roles、permissions]==========");
        String username = null;
        String userId = null;
        if (principals != null) {
            LoginUser sysUser = (LoginUser) principals.getPrimaryPrincipal();
            username = sysUser.getUsername();
            userId = sysUser.getId();
        }
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();

        // 设置用户拥有的角色集合，比如“admin,test”
        Set<String> roleSet = commonApi.queryUserRoles(username);
        //System.out.println(roleSet.toString());
        info.setRoles(roleSet);

        // 设置用户拥有的权限集合，比如“sys:role:add,sys:user:add”
        Set<String> permissionSet = commonApi.queryUserAuths(userId);
        info.addStringPermissions(permissionSet);
        //System.out.println(permissionSet);
        log.info("===============Shiro权限认证成功==============");
        return info;
    }

    /**
     * 用户信息认证是在用户进行登录的时候进行验证(不存redis)
     * 也就是说验证用户输入的账号和密码是否正确，错误抛出异常
     *
     * @param auth 用户登录的账号密码信息
     * @return 返回封装了用户信息的 AuthenticationInfo 实例
     * @throws AuthenticationException
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken auth) throws AuthenticationException {
        log.debug("===============Shiro身份认证开始============doGetAuthenticationInfo==========");
        String token = (String) auth.getCredentials();
        if (token == null) {
            HttpServletRequest req = SpringContextUtils.getHttpServletRequest();
            log.info("————————身份认证失败——————————IP地址:  "+ oConvertUtils.getIpAddrByRequest(req) +"，URL:"+req.getRequestURI());
            throw new AuthenticationException("token为空!");
        }
        // 校验token有效性
        LoginUser loginUser = null;
        try {
            loginUser = this.checkUserTokenIsEffect(token);
        } catch (AuthenticationException e) {
            JwtUtil.responseError(SpringContextUtils.getHttpServletResponse(),401,e.getMessage());
            e.printStackTrace();
            return null;
        }
        return new SimpleAuthenticationInfo(loginUser, token, getName());
    }

    /**
     * 校验token的有效性
     *
     * @param token
     */
    public LoginUser checkUserTokenIsEffect(String token) throws AuthenticationException {
        // 解密获得username，用于和数据库进行对比
        String username = JwtUtil.getUsername(token);
        if (username == null) {
            throw new AuthenticationException("token非法无效!");
        }

        // 查询用户信息
        log.debug("———校验token是否有效————checkUserTokenIsEffect——————— "+ token);
        LoginUser loginUser = TokenUtils.getLoginUser(username, commonApi, redisUtil);
        //LoginUser loginUser = commonApi.getUserByName(username);
        if (loginUser == null) {
            throw new AuthenticationException("用户不存在!");
        }
        // 判断用户状态
        if (loginUser.getStatus() != 1) {
            throw new AuthenticationException("账号已被锁定,请联系管理员!");
        }
        // 校验token是否超时失效 & 或者账号密码是否错误
        if (!jwtTokenRefresh(token, username, loginUser.getPassword())) {
            throw new AuthenticationException(CommonConstant.TOKEN_IS_INVALID_MSG);
        }
        //update-begin-author:taoyan date:20210609 for:校验用户的tenant_id和前端传过来的是否一致
        String userTenantIds = loginUser.getRelTenantIds();
        if(oConvertUtils.isNotEmpty(userTenantIds)){
            String contextTenantId = TenantContext.getTenant();
            log.debug("登录租户：" + contextTenantId);
            log.debug("用户拥有那些租户：" + userTenantIds);
             //登录用户无租户，前端header中租户ID值为 0
            String str ="0";
            if(oConvertUtils.isNotEmpty(contextTenantId) && !str.equals(contextTenantId)){
                //update-begin-author:taoyan date:20211227 for: /issues/I4O14W 用户租户信息变更判断漏洞
                String[] arr = userTenantIds.split(",");
                if(!oConvertUtils.isIn(contextTenantId, arr)){
                    boolean isAuthorization = false;
                    //========================================================================
                    // 查询用户信息（如果租户不匹配从数据库中重新查询一次用户信息）
                    String loginUserKey = CacheConstant.SYS_USERS_CACHE + "::" + username;
                    redisUtil.del(loginUserKey);
                    LoginUser loginUserFromDb = commonApi.getUserByName(username);
                    if (oConvertUtils.isNotEmpty(loginUserFromDb.getRelTenantIds())) {
                        String[] newArray = loginUserFromDb.getRelTenantIds().split(",");
                        if (oConvertUtils.isIn(contextTenantId, newArray)) { 
                            isAuthorization = true;
                        }
                    }
                    //========================================================================

                    //*********************************************
                    if(!isAuthorization){
                        log.info("租户异常——登录租户：" + contextTenantId);
                        log.info("租户异常——用户拥有租户组：" + userTenantIds);
                        throw new AuthenticationException("登录租户授权变更，请重新登陆!");
                    }
                    //*********************************************
                }
                //update-end-author:taoyan date:20211227 for: /issues/I4O14W 用户租户信息变更判断漏洞
            }
        }
        //update-end-author:taoyan date:20210609 for:校验用户的tenant_id和前端传过来的是否一致
        return loginUser;
    }

    /**
     * JWTToken刷新生命周期 （实现： 用户在线操作不掉线功能）
     * 1、登录成功后将用户的JWT生成的Token作为k、v存储到cache缓存里面(这时候k、v值一样)，缓存有效期设置为Jwt有效时间的2倍
     * 2、当该用户再次请求时，通过JWTFilter层层校验之后会进入到doGetAuthenticationInfo进行身份验证
     * 3、当该用户这次请求jwt生成的token值已经超时，但该token对应cache中的k还是存在，则表示该用户一直在操作只是JWT的token失效了，程序会给token对应的k映射的v值重新生成JWTToken并覆盖v值，该缓存生命周期重新计算
     * 4、当该用户这次请求jwt在生成的token值已经超时，并在cache中不存在对应的k，则表示该用户账户空闲超时，返回用户信息已失效，请重新登录。
     * 注意： 前端请求Header中设置Authorization保持不变，校验有效性以缓存中的token为准。
     *       用户过期时间 = Jwt有效时间 * 2。
     *
     * @param userName
     * @param passWord
     * @return
     */
    public boolean jwtTokenRefresh(String token, String userName, String passWord) {
        String cacheToken = String.valueOf(redisUtil.get(CommonConstant.PREFIX_USER_TOKEN + token));
        if (oConvertUtils.isNotEmpty(cacheToken)) {
            // 校验token有效性
            if (!JwtUtil.verify(cacheToken, userName, passWord)) {
                String newAuthorization = JwtUtil.sign(userName, passWord);
                // 设置超时时间
                redisUtil.set(CommonConstant.PREFIX_USER_TOKEN + token, newAuthorization);
                redisUtil.expire(CommonConstant.PREFIX_USER_TOKEN + token, JwtUtil.EXPIRE_TIME *2 / 1000);
                // 刷新登录的token和租户id的绑定过期时间
                redisUtil.expire(CommonConstant.PREFIX_USER_TENANT+token,JwtUtil.EXPIRE_TIME * 2 / 1000);
                log.debug("——————————用户在线操作，更新token保证不掉线—————————jwtTokenRefresh——————— "+ token);
            }
            //update-begin--Author:scott  Date:20191005  for：解决每次请求，都重写redis中 token缓存问题
//			else {
//				// 设置超时时间
//				redisUtil.set(CommonConstant.PREFIX_USER_TOKEN + token, cacheToken);
//				redisUtil.expire(CommonConstant.PREFIX_USER_TOKEN + token, JwtUtil.EXPIRE_TIME / 1000);
//			}
            //update-end--Author:scott  Date:20191005   for：解决每次请求，都重写redis中 token缓存问题
            return true;
        }

        //redis中不存在此TOEKN，说明token非法返回false
        return false;
    }

    /**
     * 清除当前用户的权限认证缓存
     *
     * @param principals 权限信息
     */
    @Override
    public void clearCache(PrincipalCollection principals) {
        super.clearCache(principals);
    }

}
