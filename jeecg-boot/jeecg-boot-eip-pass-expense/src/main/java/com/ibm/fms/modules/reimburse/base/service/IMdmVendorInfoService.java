package com.ibm.fms.modules.reimburse.base.service;

import com.ibm.fms.modules.reimburse.base.entity.MdmVendorCompanyInfo;
import com.ibm.fms.modules.reimburse.base.entity.MdmVendorAccountInfo;
import com.ibm.fms.modules.reimburse.base.entity.MdmVendorContactsInfo;
import com.ibm.fms.modules.reimburse.base.entity.MdmVendorInfo;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * @Description: 主数据供应商信息
 * @Author: jeecg-boot
 * @Date:   2020-01-17
 * @Version: V1.0
 */
public interface IMdmVendorInfoService extends IService<MdmVendorInfo> {

	/**
	 * 删除一对多
	 */
	public void delMain (String id);
	
	/**
	 * 批量删除一对多
	 */
	public void delBatchMain (Collection<? extends Serializable> idList);


}