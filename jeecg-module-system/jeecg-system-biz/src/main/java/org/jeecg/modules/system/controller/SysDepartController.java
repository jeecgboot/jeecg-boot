package org.jeecg.modules.system.controller;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.config.TenantContext;
import org.jeecg.common.constant.CacheConstant;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.ImportExcelUtil;
import org.jeecg.common.util.YouBianCodeUtil;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.mybatis.MybatisPlusSaasConfig;
import org.jeecg.modules.system.entity.SysDepart;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.model.DepartIdModel;
import org.jeecg.modules.system.model.SysDepartTreeModel;
import org.jeecg.modules.system.service.ISysDepartService;
import org.jeecg.modules.system.service.ISysUserDepartService;
import org.jeecg.modules.system.service.ISysUserService;
import org.jeecg.modules.system.vo.SysDepartExportVo;
import org.jeecg.modules.system.vo.lowapp.ExportDepartVo;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 * <p>
 * 部门表 前端控制器
 * <p>
 * 
 * @Author: Steve @Since： 2019-01-22
 */
@RestController
@RequestMapping("/sys/sysDepart")
@Slf4j
public class SysDepartController {

	@Autowired
	private ISysDepartService sysDepartService;
	@Autowired
	public RedisTemplate<String, Object> redisTemplate;
	@Autowired
	private ISysUserService sysUserService;
	@Autowired
	private ISysUserDepartService sysUserDepartService;
	/**
	 * 查询数据 查出我的部门,并以树结构数据格式响应给前端
	 *
	 * @return
	 */
	@RequestMapping(value = "/queryMyDeptTreeList", method = RequestMethod.GET)
	public Result<List<SysDepartTreeModel>> queryMyDeptTreeList() {
		Result<List<SysDepartTreeModel>> result = new Result<>();
		LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
		try {
			if(oConvertUtils.isNotEmpty(user.getUserIdentity()) && user.getUserIdentity().equals( CommonConstant.USER_IDENTITY_2 )){
				//update-begin--Author:liusq  Date:20210624  for:部门查询ids为空后的前端显示问题 issues/I3UD06
				String departIds = user.getDepartIds();
				if(StringUtils.isNotBlank(departIds)){
					List<SysDepartTreeModel> list = sysDepartService.queryMyDeptTreeList(departIds);
					result.setResult(list);
				}
				//update-end--Author:liusq  Date:20210624  for:部门查询ids为空后的前端显示问题 issues/I3UD06
				result.setMessage(CommonConstant.USER_IDENTITY_2.toString());
				result.setSuccess(true);
			}else{
				result.setMessage(CommonConstant.USER_IDENTITY_1.toString());
				result.setSuccess(true);
			}
		} catch (Exception e) {
			log.error(e.getMessage(),e);
		}
		return result;
	}

	/**
	 * 查询数据 查出所有部门,并以树结构数据格式响应给前端
	 * 
	 * @return
	 */
	@RequestMapping(value = "/queryTreeList", method = RequestMethod.GET)
	public Result<List<SysDepartTreeModel>> queryTreeList(@RequestParam(name = "ids", required = false) String ids) {
		Result<List<SysDepartTreeModel>> result = new Result<>();
		try {
			// 从内存中读取
//			List<SysDepartTreeModel> list =FindsDepartsChildrenUtil.getSysDepartTreeList();
//			if (CollectionUtils.isEmpty(list)) {
//				list = sysDepartService.queryTreeList();
//			}
			if(oConvertUtils.isNotEmpty(ids)){
				List<SysDepartTreeModel> departList = sysDepartService.queryTreeList(ids);
				result.setResult(departList);
			}else{
				List<SysDepartTreeModel> list = sysDepartService.queryTreeList();
				result.setResult(list);
			}
			result.setSuccess(true);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
		}
		return result;
	}

	/**
	 * 异步查询部门list
	 * @param parentId 父节点 异步加载时传递
	 * @param ids 前端回显是传递
	 * @param primaryKey 主键字段（id或者orgCode）
	 * @return
	 */
	@RequestMapping(value = "/queryDepartTreeSync", method = RequestMethod.GET)
	public Result<List<SysDepartTreeModel>> queryDepartTreeSync(@RequestParam(name = "pid", required = false) String parentId,@RequestParam(name = "ids", required = false) String ids, @RequestParam(name = "primaryKey", required = false) String primaryKey) {
		Result<List<SysDepartTreeModel>> result = new Result<>();
		try {
			List<SysDepartTreeModel> list = sysDepartService.queryTreeListByPid(parentId,ids, primaryKey);
			result.setResult(list);
			result.setSuccess(true);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			result.setSuccess(false);
			result.setMessage("查询失败");
		}
		return result;
	}

	/**
	 * 获取某个部门的所有父级部门的ID
	 *
	 * @param departId 根据departId查
	 * @param orgCode  根据orgCode查，departId和orgCode必须有一个不为空
	 */
	@GetMapping("/queryAllParentId")
	public Result queryParentIds(
			@RequestParam(name = "departId", required = false) String departId,
			@RequestParam(name = "orgCode", required = false) String orgCode) {
		try {
			JSONObject data;
			if (oConvertUtils.isNotEmpty(departId)) {
				data = sysDepartService.queryAllParentIdByDepartId(departId);
			} else if (oConvertUtils.isNotEmpty(orgCode)) {
				data = sysDepartService.queryAllParentIdByOrgCode(orgCode);
			} else {
				return Result.error("departId 和 orgCode 不能都为空！");
			}
			return Result.OK(data);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return Result.error(e.getMessage());
		}
	}

	/**
	 * 添加新数据 添加用户新建的部门对象数据,并保存到数据库
	 * 
	 * @param sysDepart
	 * @return
	 */
    @RequiresPermissions("system:depart:add")
	@RequestMapping(value = "/add", method = RequestMethod.POST)
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
	public Result<SysDepart> add(@RequestBody SysDepart sysDepart, HttpServletRequest request) {
		Result<SysDepart> result = new Result<SysDepart>();
		String username = JwtUtil.getUserNameByToken(request);
		try {
			sysDepart.setCreateBy(username);
			sysDepartService.saveDepartData(sysDepart, username);
			//清除部门树内存
			// FindsDepartsChildrenUtil.clearSysDepartTreeList();
			// FindsDepartsChildrenUtil.clearDepartIdModel();
			result.success("添加成功！");
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			result.error500("操作失败");
		}
		return result;
	}

	/**
	 * 编辑数据 编辑部门的部分数据,并保存到数据库
	 * 
	 * @param sysDepart
	 * @return
	 */
    @RequiresPermissions("system:depart:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
	public Result<SysDepart> edit(@RequestBody SysDepart sysDepart, HttpServletRequest request) {
		String username = JwtUtil.getUserNameByToken(request);
		sysDepart.setUpdateBy(username);
		Result<SysDepart> result = new Result<SysDepart>();
		SysDepart sysDepartEntity = sysDepartService.getById(sysDepart.getId());
		if (sysDepartEntity == null) {
			result.error500("未找到对应实体");
		} else {
			boolean ok = sysDepartService.updateDepartDataById(sysDepart, username);
			// TODO 返回false说明什么？
			if (ok) {
				//清除部门树内存
				//FindsDepartsChildrenUtil.clearSysDepartTreeList();
				//FindsDepartsChildrenUtil.clearDepartIdModel();
				result.success("修改成功!");
			}
		}
		return result;
	}
	
	 /**
     *   通过id删除
    * @param id
    * @return
    */
    @RequiresPermissions("system:depart:delete")
    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
   public Result<SysDepart> delete(@RequestParam(name="id",required=true) String id) {

       Result<SysDepart> result = new Result<SysDepart>();
       SysDepart sysDepart = sysDepartService.getById(id);
       if(sysDepart==null) {
           result.error500("未找到对应实体");
       }else {
           sysDepartService.deleteDepart(id);
			//清除部门树内存
		   //FindsDepartsChildrenUtil.clearSysDepartTreeList();
		   // FindsDepartsChildrenUtil.clearDepartIdModel();
		   result.success("删除成功!");
       }
       return result;
   }


	/**
	 * 批量删除 根据前端请求的多个ID,对数据库执行删除相关部门数据的操作
	 * 
	 * @param ids
	 * @return
	 */
    @RequiresPermissions("system:depart:deleteBatch")
	@RequestMapping(value = "/deleteBatch", method = RequestMethod.DELETE)
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
	public Result<SysDepart> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {

		Result<SysDepart> result = new Result<SysDepart>();
		if (ids == null || "".equals(ids.trim())) {
			result.error500("参数不识别！");
		} else {
			this.sysDepartService.deleteBatchWithChildren(Arrays.asList(ids.split(",")));
			result.success("删除成功!");
		}
		return result;
	}

	/**
	 * 查询数据 添加或编辑页面对该方法发起请求,以树结构形式加载所有部门的名称,方便用户的操作
	 * 
	 * @return
	 */
	@RequestMapping(value = "/queryIdTree", method = RequestMethod.GET)
	public Result<List<DepartIdModel>> queryIdTree() {
//		Result<List<DepartIdModel>> result = new Result<List<DepartIdModel>>();
//		List<DepartIdModel> idList;
//		try {
//			idList = FindsDepartsChildrenUtil.wrapDepartIdModel();
//			if (idList != null && idList.size() > 0) {
//				result.setResult(idList);
//				result.setSuccess(true);
//			} else {
//				sysDepartService.queryTreeList();
//				idList = FindsDepartsChildrenUtil.wrapDepartIdModel();
//				result.setResult(idList);
//				result.setSuccess(true);
//			}
//			return result;
//		} catch (Exception e) {
//			log.error(e.getMessage(),e);
//			result.setSuccess(false);
//			return result;
//		}
		Result<List<DepartIdModel>> result = new Result<>();
		try {
			List<DepartIdModel> list = sysDepartService.queryDepartIdTreeList();
			result.setResult(list);
			result.setSuccess(true);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
		}
		return result;
	}
	 
	/**
	 * <p>
	 * 部门搜索功能方法,根据关键字模糊搜索相关部门
	 * </p>
	 * 
	 * @param keyWord
	 * @return
	 */
	@RequestMapping(value = "/searchBy", method = RequestMethod.GET)
	public Result<List<SysDepartTreeModel>> searchBy(@RequestParam(name = "keyWord", required = true) String keyWord,@RequestParam(name = "myDeptSearch", required = false) String myDeptSearch) {
		Result<List<SysDepartTreeModel>> result = new Result<List<SysDepartTreeModel>>();
		//部门查询，myDeptSearch为1时为我的部门查询，登录用户为上级时查只查负责部门下数据
		LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
		String departIds = null;
		if(oConvertUtils.isNotEmpty(user.getUserIdentity()) && user.getUserIdentity().equals( CommonConstant.USER_IDENTITY_2 )){
			departIds = user.getDepartIds();
		}
		List<SysDepartTreeModel> treeList = this.sysDepartService.searchByKeyWord(keyWord,myDeptSearch,departIds);
		if (treeList == null || treeList.size() == 0) {
			result.setSuccess(false);
			result.setMessage("未查询匹配数据！");
			return result;
		}
		result.setResult(treeList);
		return result;
	}


	/**
     * 导出excel
     *
     * @param request
     */
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(SysDepart sysDepart,HttpServletRequest request) {
		if(MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL){
			sysDepart.setTenantId(oConvertUtils.getInt(TenantContext.getTenant(), 0));
		} else {
			sysDepart.setTenantId(0); //added by yardstown for issue#5972, to init no multitenant as 0
		}
		//------------------------------------------------------------------------------------------------
		
		//update-begin---author:wangshuai---date:2023-10-19---for:【QQYUN-5482】系统的部门导入导出也可以改成敲敲云模式的部门路径---
		//// Step.1 组装查询条件
		//QueryWrapper<SysDepart> queryWrapper = QueryGenerator.initQueryWrapper(sysDepart, request.getParameterMap());
		//Step.1 AutoPoi 导出Excel
		ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
        //List<SysDepart> pageList = sysDepartService.list(queryWrapper);
        //按字典排序
        //Collections.sort(pageList, new Comparator<SysDepart>() {
            //@Override
			//public int compare(SysDepart arg0, SysDepart arg1) {
				//return arg0.getOrgCode().compareTo(arg1.getOrgCode());
			//}
		//});
		//step.2 组装导出数据
		Integer tenantId = sysDepart == null ? null : sysDepart.getTenantId();
		List<SysDepartExportVo> sysDepartExportVos = sysDepartService.getExportDepart(tenantId);
        //导出文件名称
        mv.addObject(NormalExcelConstants.FILE_NAME, "部门列表");
        mv.addObject(NormalExcelConstants.CLASS, SysDepartExportVo.class);
        LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        mv.addObject(NormalExcelConstants.PARAMS, new ExportParams("导入规则：\n" +
				"1、标题为第三行，部门路径和部门名称的标题不允许修改，否则会匹配失败；第四行为数据填写范围;\n" +
				"2、部门路径用英文字符/分割，部门名称为部门路径的最后一位;\n" +
				"3、部门从一级名称开始创建，如果有同级就需要多添加一行，如研发部/研发一部;研发部/研发二部;\n" +
				"4、自定义的部门编码需要满足规则才能导入。如一级部门编码为A01,那么子部门为A01A01,同级子部门为A01A02,编码固定为三位，首字母为A-Z,后两位为数字0-99，依次递增;", "导出人:"+user.getRealname(), "导出信息"));
        mv.addObject(NormalExcelConstants.DATA_LIST, sysDepartExportVos);
		//update-end---author:wangshuai---date:2023-10-19---for:【QQYUN-5482】系统的部门导入导出也可以改成敲敲云模式的部门路径---
        
		return mv;
    }

    /**
     * 通过excel导入数据
	 * 部门导入方案1: 通过机构编码来计算出部门的父级ID,维护上下级关系;
	 * 部门导入方案2: 你也可以改造下程序,机构编码直接导入,先不设置父ID;全部导入后,写一个sql,补下父ID;
     *
     * @param request
     * @param response
     * @return
     */
    @RequiresPermissions("system:depart:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
		List<String> errorMessageList = new ArrayList<>();
		//List<SysDepart> listSysDeparts = null;
		List<SysDepartExportVo> listSysDeparts = null;
        Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
        for (Map.Entry<String, MultipartFile> entity : fileMap.entrySet()) {
            // 获取上传文件对象
            MultipartFile file = entity.getValue();
            ImportParams params = new ImportParams();
            params.setTitleRows(2);
            params.setHeadRows(1);
            params.setNeedSave(true);
            try {
				//update-begin---author:wangshuai---date:2023-10-20---for: 注释掉原来的导入部门的逻辑---
//            	// orgCode编码长度
//            	int codeLength = YouBianCodeUtil.ZHANWEI_LENGTH;
//                listSysDeparts = ExcelImportUtil.importExcel(file.getInputStream(), SysDepart.class, params);
//                //按长度排序
//                Collections.sort(listSysDeparts, new Comparator<SysDepart>() {
//                    @Override
//					public int compare(SysDepart arg0, SysDepart arg1) {
//                    	return arg0.getOrgCode().length() - arg1.getOrgCode().length();
//                    }
//                });
//
//                int num = 0;
//                for (SysDepart sysDepart : listSysDeparts) {
//                	String orgCode = sysDepart.getOrgCode();
//                	if(orgCode.length() > codeLength) {
//                		String parentCode = orgCode.substring(0, orgCode.length()-codeLength);
//                		QueryWrapper<SysDepart> queryWrapper = new QueryWrapper<SysDepart>();
//                		queryWrapper.eq("org_code", parentCode);
//                		try {
//                		SysDepart parentDept = sysDepartService.getOne(queryWrapper);
//                		if(!parentDept.equals(null)) {
//							sysDepart.setParentId(parentDept.getId());
//							//更新父级部门不是叶子结点
//							sysDepartService.updateIzLeaf(parentDept.getId(),CommonConstant.NOT_LEAF);
//						} else {
//							sysDepart.setParentId("");
//						}
//                		}catch (Exception e) {
//                			//没有查找到parentDept
//                		}
//                	}else{
//                		sysDepart.setParentId("");
//					}
//                    //update-begin---author:liusq   Date:20210223  for：批量导入部门以后，不能追加下一级部门 #2245------------
//					sysDepart.setOrgType(sysDepart.getOrgCode().length()/codeLength+"");
//                    //update-end---author:liusq   Date:20210223  for：批量导入部门以后，不能追加下一级部门 #2245------------
//					sysDepart.setDelFlag(CommonConstant.DEL_FLAG_0.toString());
//                    //update-begin---author:wangshuai ---date:20220105  for：[JTC-363]部门导入 机构类别没有时导入失败，赋默认值------------
//					if(oConvertUtils.isEmpty(sysDepart.getOrgCategory())){
//					    sysDepart.setOrgCategory("1");
//                    }
//                    //update-end---author:wangshuai ---date:20220105  for：[JTC-363]部门导入 机构类别没有时导入失败，赋默认值------------
//					ImportExcelUtil.importDateSaveOne(sysDepart, ISysDepartService.class, errorMessageList, num, CommonConstant.SQL_INDEX_UNIQ_DEPART_ORG_CODE);
//					num++;
//                }
				//update-end---author:wangshuai---date:2023-10-20---for: 注释掉原来的导入部门的逻辑---
				
				//update-begin---author:wangshuai---date:2023-10-19---for:【QQYUN-5482】系统的部门导入导出也可以改成敲敲云模式的部门路径---
				listSysDeparts = ExcelImportUtil.importExcel(file.getInputStream(), SysDepartExportVo.class, params);
				sysDepartService.importSysDepart(listSysDeparts,errorMessageList);
				//update-end---author:wangshuai---date:2023-10-19---for:【QQYUN-5482】系统的部门导入导出也可以改成敲敲云模式的部门路径---
				
				//清空部门缓存
				Set keys3 = redisTemplate.keys(CacheConstant.SYS_DEPARTS_CACHE + "*");
				Set keys4 = redisTemplate.keys(CacheConstant.SYS_DEPART_IDS_CACHE + "*");
				redisTemplate.delete(keys3);
				redisTemplate.delete(keys4);
				return ImportExcelUtil.imporReturnRes(errorMessageList.size(), listSysDeparts.size() - errorMessageList.size(), errorMessageList);
            } catch (Exception e) {
                log.error(e.getMessage(),e);
                return Result.error("文件导入失败:"+e.getMessage());
            } finally {
                try {
                    file.getInputStream().close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return Result.error("文件导入失败！");
    }


	/**
	 * 查询所有部门信息
	 * @return
	 */
	@GetMapping("listAll")
	public Result<List<SysDepart>> listAll(@RequestParam(name = "id", required = false) String id) {
		Result<List<SysDepart>> result = new Result<>();
		LambdaQueryWrapper<SysDepart> query = new LambdaQueryWrapper<SysDepart>();
		query.orderByAsc(SysDepart::getOrgCode);
		if(oConvertUtils.isNotEmpty(id)){
			String[] arr = id.split(",");
			query.in(SysDepart::getId,arr);
		}
		List<SysDepart> ls = this.sysDepartService.list(query);
		result.setSuccess(true);
		result.setResult(ls);
		return result;
	}
	/**
	 * 查询数据 查出所有部门,并以树结构数据格式响应给前端
	 *
	 * @return
	 */
	@RequestMapping(value = "/queryTreeByKeyWord", method = RequestMethod.GET)
	public Result<Map<String,Object>> queryTreeByKeyWord(@RequestParam(name = "keyWord", required = false) String keyWord) {
		Result<Map<String,Object>> result = new Result<>();
		try {
			Map<String,Object> map=new HashMap(5);
			List<SysDepartTreeModel> list = sysDepartService.queryTreeByKeyWord(keyWord);
			//根据keyWord获取用户信息
			LambdaQueryWrapper<SysUser> queryUser = new LambdaQueryWrapper<SysUser>();
			queryUser.eq(SysUser::getDelFlag,CommonConstant.DEL_FLAG_0);
			queryUser.and(i -> i.like(SysUser::getUsername, keyWord).or().like(SysUser::getRealname, keyWord));
			List<SysUser> sysUsers = this.sysUserService.list(queryUser);
			map.put("userList",sysUsers);
			map.put("departList",list);
			result.setResult(map);
			result.setSuccess(true);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
		}
		return result;
	}

	/**
	 * 根据部门编码获取部门信息
	 *
	 * @param orgCode
	 * @return
	 */
	@GetMapping("/getDepartName")
	public Result<SysDepart> getDepartName(@RequestParam(name = "orgCode") String orgCode) {
		Result<SysDepart> result = new Result<>();
		LambdaQueryWrapper<SysDepart> query = new LambdaQueryWrapper<>();
		query.eq(SysDepart::getOrgCode, orgCode);
		SysDepart sysDepart = sysDepartService.getOne(query);
		result.setSuccess(true);
		result.setResult(sysDepart);
		return result;
	}

	/**
	 * 根据部门id获取用户信息
	 *
	 * @param id
	 * @return
	 */
	@GetMapping("/getUsersByDepartId")
	public Result<List<SysUser>> getUsersByDepartId(@RequestParam(name = "id") String id) {
		Result<List<SysUser>> result = new Result<>();
		List<SysUser> sysUsers = sysUserDepartService.queryUserByDepId(id);
		result.setSuccess(true);
		result.setResult(sysUsers);
		return result;
	}

	/**
	 * @功能：根据id 批量查询
	 * @param deptIds
	 * @return
	 */
	@RequestMapping(value = "/queryByIds", method = RequestMethod.GET)
	public Result<Collection<SysDepart>> queryByIds(@RequestParam(name = "deptIds") String deptIds) {
		Result<Collection<SysDepart>> result = new Result<>();
		String[] ids = deptIds.split(",");
		Collection<String> idList = Arrays.asList(ids);
		Collection<SysDepart> deptList = sysDepartService.listByIds(idList);
		result.setSuccess(true);
		result.setResult(deptList);
		return result;
	}

	@GetMapping("/getMyDepartList")
    public Result<List<SysDepart>> getMyDepartList(){
        List<SysDepart> list = sysDepartService.getMyDepartList();
        return Result.ok(list);
    }

	/**
	 * 异步查询部门list
	 * @param parentId 父节点 异步加载时传递
	 * @return
	 */
	@RequestMapping(value = "/queryBookDepTreeSync", method = RequestMethod.GET)
	public Result<List<SysDepartTreeModel>> queryBookDepTreeSync(@RequestParam(name = "pid", required = false) String parentId,
																 @RequestParam(name = "tenantId") Integer tenantId,
																 @RequestParam(name = "departName",required = false) String departName) {
		Result<List<SysDepartTreeModel>> result = new Result<>();
		try {
			List<SysDepartTreeModel> list = sysDepartService.queryBookDepTreeSync(parentId, tenantId, departName);
			result.setResult(list);
			result.setSuccess(true);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
		}
		return result;
	}

	/**
	 * 通过部门id和租户id获取用户 【低代码应用: 用于选择部门负责人】
	 * @param departId
	 * @return
	 */
	@GetMapping("/getUsersByDepartTenantId")
	public Result<List<SysUser>> getUsersByDepartTenantId(@RequestParam("departId") String departId){
		int tenantId = oConvertUtils.getInt(TenantContext.getTenant(), 0);
		List<SysUser> sysUserList = sysUserDepartService.getUsersByDepartTenantId(departId,tenantId);
		return Result.ok(sysUserList);
	}

	/**
	 * 导出excel【低代码应用: 用于导出部门】
	 *
	 * @param request
	 */
	@RequestMapping(value = "/appExportXls")
	public ModelAndView appExportXls(SysDepart sysDepart,HttpServletRequest request) {
		// Step.1 组装查询条件
		int tenantId = oConvertUtils.getInt(TenantContext.getTenant(), 0);
		ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
		List<ExportDepartVo> pageList = sysDepartService.getExcelDepart(tenantId);
		//Step.2 AutoPoi 导出Excel
		//导出文件名称
		mv.addObject(NormalExcelConstants.FILE_NAME, "部门列表");
		mv.addObject(NormalExcelConstants.CLASS, ExportDepartVo.class);
		LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
		mv.addObject(NormalExcelConstants.PARAMS, new ExportParams("部门列表数据", "导出人:"+user.getRealname(), "导出信息"));
		mv.addObject(NormalExcelConstants.DATA_LIST, pageList);
		return mv;
	}

	/**
	 * 导入excel【低代码应用: 用于导出部门】
	 *
	 * @param request
	 */
	@RequestMapping(value = "/appImportExcel", method = RequestMethod.POST)
	@CacheEvict(value= {CacheConstant.SYS_DEPARTS_CACHE,CacheConstant.SYS_DEPART_IDS_CACHE}, allEntries=true)
	public Result<?> appImportExcel(HttpServletRequest request, HttpServletResponse response) {
		MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
		List<String> errorMessageList = new ArrayList<>();
		List<ExportDepartVo> listSysDeparts = null;
		Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
		for (Map.Entry<String, MultipartFile> entity : fileMap.entrySet()) {
			// 获取上传文件对象
			MultipartFile file = entity.getValue();
			ImportParams params = new ImportParams();
			params.setTitleRows(2);
			params.setHeadRows(1);
			params.setNeedSave(true);
			try {
				listSysDeparts = ExcelImportUtil.importExcel(file.getInputStream(), ExportDepartVo.class, params);
				sysDepartService.importExcel(listSysDeparts,errorMessageList);
				//清空部门缓存
				Set keys3 = redisTemplate.keys(CacheConstant.SYS_DEPARTS_CACHE + "*");
				Set keys4 = redisTemplate.keys(CacheConstant.SYS_DEPART_IDS_CACHE + "*");
				redisTemplate.delete(keys3);
				redisTemplate.delete(keys4);
				return ImportExcelUtil.imporReturnRes(errorMessageList.size(), listSysDeparts.size() - errorMessageList.size(), errorMessageList);
			} catch (Exception e) {
				log.error(e.getMessage(),e);
				return Result.error("文件导入失败:"+e.getMessage());
			} finally {
				try {
					file.getInputStream().close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return Result.error("文件导入失败！");
	}
	
}
