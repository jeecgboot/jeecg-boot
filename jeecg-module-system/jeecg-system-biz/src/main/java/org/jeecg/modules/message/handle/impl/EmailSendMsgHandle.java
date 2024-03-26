package org.jeecg.modules.message.handle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.StaticConfig;
import org.jeecg.modules.message.handle.ISendMsgHandle;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Set;

/**
 * @Description: 邮箱发送信息
 * @author: jeecg-boot
 */
@Slf4j
@Component("emailSendMsgHandle")
public class EmailSendMsgHandle implements ISendMsgHandle {
    static String emailFrom;

    public static void setEmailFrom(String emailFrom) {
        EmailSendMsgHandle.emailFrom = emailFrom;
    }

    @Autowired
    SysUserMapper sysUserMapper;

    @Autowired
    private RedisUtil redisUtil;

    /**
     * 真实姓名变量
     */
    private static final String  realNameExp = "{REALNAME}";



    @Override
    public void sendMsg(String esReceiver, String esTitle, String esContent) {
        JavaMailSender mailSender = (JavaMailSender) SpringContextUtils.getBean("mailSender");
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        //update-begin-author：taoyan date:20200811 for:配置类数据获取
        if(oConvertUtils.isEmpty(emailFrom)){
            StaticConfig staticConfig = SpringContextUtils.getBean(StaticConfig.class);
            setEmailFrom(staticConfig.getEmailFrom());
        }
        //update-end-author：taoyan date:20200811 for:配置类数据获取
        try {
            helper = new MimeMessageHelper(message, true);
            // 设置发送方邮箱地址
            helper.setFrom(emailFrom);
            helper.setTo(esReceiver);
            helper.setSubject(esTitle);
            helper.setText(esContent, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void sendMessage(MessageDTO messageDTO) {
        String[] arr = messageDTO.getToUser().split(",");
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<SysUser>().in(SysUser::getUsername, arr);
        List<SysUser> list = sysUserMapper.selectList(query);
        String content = messageDTO.getContent();
        String title = messageDTO.getTitle();
        for(SysUser user: list){
            String email = user.getEmail();
            if (ObjectUtils.isEmpty(email)) {
                continue;
            }
            content=replaceContent(user,content);
            log.info("邮件内容："+ content);
            sendMsg(email, title, content);
        }
        
        //update-begin-author:taoyan date:2023-6-20 for: QQYUN-5557【简流】通知节点 发送邮箱 表单上有一个邮箱字段，流程中，邮件发送节点，邮件接收人 不可选择邮箱
        Set<String> toEmailList = messageDTO.getToEmailList();
        if(toEmailList!=null && toEmailList.size()>0){
            for(String email: toEmailList){
                if (ObjectUtils.isEmpty(email)) {
                    continue;
                }
                log.info("邮件内容："+ content);
                sendMsg(email, title, content);
            }
        }
        //update-end-author:taoyan date:2023-6-20 for: QQYUN-5557【简流】通知节点 发送邮箱 表单上有一个邮箱字段，流程中，邮件发送节点，邮件接收人 不可选择邮箱
        
        //发送给抄送人
        sendMessageToCopyUser(messageDTO);
    }

    /**
     * 发送邮件给抄送人
     * @param messageDTO
     */
    public void sendMessageToCopyUser(MessageDTO messageDTO) {
        String copyToUser = messageDTO.getCopyToUser();
        if(ObjectUtils.isNotEmpty(copyToUser)) {
            LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<SysUser>().in(SysUser::getUsername, copyToUser.split(","));
            List<SysUser> list = sysUserMapper.selectList(query);
            String content = messageDTO.getContent();
            String title = messageDTO.getTitle();

            for (SysUser user : list) {
                String email = user.getEmail();
                if (ObjectUtils.isEmpty(email)) {
                    continue;
                }
                content=replaceContent(user,content);
                log.info("邮件内容：" + content);
                
            //update-begin-author:taoyan date:2023-6-20 for: QQYUN-5557【简流】通知节点 发送邮箱 表单上有一个邮箱字段，流程中，邮件发送节点，邮件接收人 不可选择邮箱
                sendEmail(email, content, title);
            }

            Set<String> ccEmailList = messageDTO.getCcEmailList();
            if(ccEmailList!=null && ccEmailList.size()>0){
                for(String email: ccEmailList){
                    if (ObjectUtils.isEmpty(email)) {
                        continue;
                    }
                    log.info("邮件内容："+ content);
                    sendEmail(email, content, title);
                }
            }
            
        }
    }

    /**
     * 发送邮件给抄送人调用
     * @param email
     * @param content
     * @param title
     */
    private void sendEmail(String email, String content, String title){
        JavaMailSender mailSender = (JavaMailSender) SpringContextUtils.getBean("mailSender");
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        if (oConvertUtils.isEmpty(emailFrom)) {
            StaticConfig staticConfig = SpringContextUtils.getBean(StaticConfig.class);
            setEmailFrom(staticConfig.getEmailFrom());
        }
        try {
            helper = new MimeMessageHelper(message, true);
            // 设置发送方邮箱地址
            helper.setFrom(emailFrom);
            helper.setTo(email);
            //设置抄送人
            helper.setCc(email);
            helper.setSubject(title);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }
    //update-end-author:taoyan date:2023-6-20 for: QQYUN-5557【简流】通知节点 发送邮箱 表单上有一个邮箱字段，流程中，邮件发送节点，邮件接收人 不可选择邮箱
    

    /**
     * 替换邮件内容变量
     * @param user
     * @param content
     * @return
     */
    private String replaceContent(SysUser user,String content){
        if (content.indexOf(realNameExp) > 0) {
            content = content.replace("$"+realNameExp,user.getRealname()).replace(realNameExp, user.getRealname());
        }
        if (content.indexOf(CommonConstant.LOGIN_TOKEN) > 0) {
            String token = getToken(user);
            try {
                content = content.replace(CommonConstant.LOGIN_TOKEN, URLEncoder.encode(token, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                log.error("邮件消息token编码失败", e.getMessage());
            }
        }
        return content;
    }

    /**
     * 获取token
     * @param user
     * @return
     */
    private String getToken(SysUser user) {
        // 生成token
        String token = JwtUtil.sign(user.getUsername(), user.getPassword());
        redisUtil.set(CommonConstant.PREFIX_USER_TOKEN + token, token);
        // 设置超时时间 1个小时
        redisUtil.expire(CommonConstant.PREFIX_USER_TOKEN + token, JwtUtil.EXPIRE_TIME * 1 / 1000);
        // 刷新登录的token和租户id的绑定过期时间
        redisUtil.expire(CommonConstant.PREFIX_USER_TENANT+token,JwtUtil.EXPIRE_TIME * 1 / 1000);
        return token;
    }
}
