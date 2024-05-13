package org.jeecg.modules.business.controller.admin;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.business.entity.Client;
import org.jeecg.modules.business.entity.ClientCategory;
import org.jeecg.modules.business.service.IUserClientService;
import org.jeecg.modules.system.service.ISysDepartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Api(tags = "UserClient")
@RestController
@RequestMapping("/userClient")
@Slf4j
public class UserClientController {
    @Autowired
    private IUserClientService userClientService;
    @Autowired
    private ISysDepartService sysDepartService;
    @Autowired
    private Environment env;
    /**
     * Checks if the user is a client or internal user
     * @return the client's info OR a list of clients
     */
    @GetMapping(value = "/getClient")
    public Result<?> getClientByUserId() {
        String companyOrgCode = sysDepartService.queryCodeByDepartName(env.getProperty("company.orgName"));
        LoginUser loginUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        String orgCode = loginUser.getOrgCode();
        String userId = loginUser.getId();
        Client client = userClientService.getClientMinInfoByUserId(userId);
        if(client == null) {
            if(orgCode.equals(companyOrgCode)) {
                Map<String, List<Client>> internalClientList = new HashMap<>();
                internalClientList.put("internal", userClientService.listClients());
                return Result.OK("internal usage", internalClientList);
            }
            else
                return Result.error(403, "Access Denied.");
        }
        Map<String, Client> clientMap = new HashMap<>();
        clientMap.put("client", client);
        return Result.ok(clientMap);
    }
    @GetMapping(value = "/getSelfServiceClients")
    public Result<?> getSelfServiceClients() {
        return Result.ok(userClientService.getClientsByCategory(ClientCategory.CategoryName.SELF_SERVICE.getName()));
    }
}
