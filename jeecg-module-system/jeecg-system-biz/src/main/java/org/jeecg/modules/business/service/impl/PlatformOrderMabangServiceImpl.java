package org.jeecg.modules.business.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.business.domain.api.mabang.dochangeorder.ChangeOrderRequest;
import org.jeecg.modules.business.domain.api.mabang.dochangeorder.ChangeOrderRequestBody;
import org.jeecg.modules.business.domain.api.mabang.dochangeorder.ChangeOrderResponse;
import org.jeecg.modules.business.domain.api.mabang.getorderlist.Order;
import org.jeecg.modules.business.domain.api.mabang.getorderlist.OrderItem;
import org.jeecg.modules.business.domain.api.mabang.getorderlist.OrderStatus;
import org.jeecg.modules.business.domain.api.mabang.orderDoOrderAbnormal.OrderSuspendRequest;
import org.jeecg.modules.business.domain.api.mabang.orderDoOrderAbnormal.OrderSuspendRequestBody;
import org.jeecg.modules.business.domain.api.mabang.orderDoOrderAbnormal.OrderSuspendResponse;
import org.jeecg.modules.business.domain.job.ThrottlingExecutorService;
import org.jeecg.modules.business.entity.PlatformOrder;
import org.jeecg.modules.business.mapper.PlatformOrderMabangMapper;
import org.jeecg.modules.business.service.IPlatformOrderMabangService;
import org.jeecg.modules.business.vo.PlatformOrderOperation;
import org.jeecg.modules.business.vo.Responses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * @Description: 平台订单表
 * @Author: jeecg-boot
 * @Date: 2021-04-08
 * @Version: V1.0
 */
@Service
@Slf4j
public class PlatformOrderMabangServiceImpl extends ServiceImpl<PlatformOrderMabangMapper, Order> implements IPlatformOrderMabangService {
    @Autowired
    private PlatformOrderMabangMapper platformOrderMabangMapper;

    private static final Integer DEFAULT_NUMBER_OF_THREADS = 2;
    private static final Integer MABANG_API_RATE_LIMIT_PER_MINUTE = 10;
    @Override
    @Transactional
    public void saveOrderFromMabang(List<Order> orders) {
        if (orders.isEmpty()) {
            return;
        }
        // find orders that already exist in DB
        List<String> allPlatformOrderId = orders.stream()
                .map(Order::getPlatformOrderId)
                .collect(toList());
        List<PlatformOrder> existingOrders = platformOrderMabangMapper.searchExistence(allPlatformOrderId);

        Map<String, PlatformOrder> platformOrderIDToExistOrders = existingOrders.stream()
                .collect(
                        Collectors.toMap(
                                PlatformOrder::getPlatformOrderId, Function.identity()
                        )
                );

        ArrayList<Order> newOrders = new ArrayList<>();
        ArrayList<Order> oldOrders = new ArrayList<>();
        ArrayList<Order> ordersFromShippedToCompleted = new ArrayList<>();
        ArrayList<Order> invoicedShippedOrders = new ArrayList<>();
        ArrayList<Order> obsoleteOrders = new ArrayList<>();
        for (Order retrievedOrder : orders) {
            PlatformOrder orderInDatabase = platformOrderIDToExistOrders.get(retrievedOrder.getPlatformOrderId());
            if (orderInDatabase == null) {
                newOrders.add(retrievedOrder);
            } else {
                // re-affect retrieved orders with ID in database
                retrievedOrder.setId(orderInDatabase.getId());
                // For orders that pass from Shipped to Completed, we must NOT delete then re-insert their contents
                // Because we would lose all calculated fees (shipping, vat, service)
                if (retrievedOrder.getStatus().equals(OrderStatus.Completed.getCode())) {
                    if (orderInDatabase.getErpStatus().equals(OrderStatus.Shipped.getCode())
                            || orderInDatabase.getErpStatus().equals(OrderStatus.Preparing.getCode())
                            || orderInDatabase.getErpStatus().equals(OrderStatus.Pending.getCode())
                    ) {
                        ordersFromShippedToCompleted.add(retrievedOrder);
                    }
                } else if (retrievedOrder.getStatus().equals(OrderStatus.Shipped.getCode())) {
                    if (orderInDatabase.getErpStatus().equals(OrderStatus.Preparing.getCode())
                            || orderInDatabase.getErpStatus().equals(OrderStatus.Pending.getCode())
                            || orderInDatabase.getErpStatus().equals(OrderStatus.Obsolete.getCode())
                            || (retrievedOrder.getTrackingNumber() != null && orderInDatabase.getTrackingNumber() != null &&
                                    !retrievedOrder.getTrackingNumber().equalsIgnoreCase(orderInDatabase.getTrackingNumber()))
                    ) {
                        // If order wasn't invoiced pre-shipping, we can remove and re-insert contents
                        if (orderInDatabase.getShippingInvoiceNumber() == null) {
                            oldOrders.add(retrievedOrder);
                        } else {
                            invoicedShippedOrders.add(retrievedOrder);
                        }
                    }
                } else {
                    // If order is shipped or already has shipping invoice number(pre-shipping), don't update anything,
                    // wait until it becomes Completed then only update status
                    if (!orderInDatabase.getErpStatus().equals(OrderStatus.Shipped.getCode())
                            && orderInDatabase.getShippingInvoiceNumber() == null) {
                        // for old orders get their id, update their attributes
                        oldOrders.add(retrievedOrder);
                    }
                    if (retrievedOrder.getStatus().equals(OrderStatus.Obsolete.getCode())) {
                        obsoleteOrders.add(retrievedOrder);
                    }
                }
            }
        }
        orders.clear();

        /* for new orders, insert them to DB and their children */
        List<OrderItem> allNewItems = prepareItems(newOrders);
        try {
            if (newOrders.size() != 0) {
                log.info("{} orders to be inserted/updated.", newOrders.size());
                platformOrderMabangMapper.insertOrdersFromMabang(newOrders);
            }
            if (allNewItems.size() != 0) {
                platformOrderMabangMapper.insertOrderItemsFromMabang(allNewItems);
                log.info("{} order items to be inserted/updated.", allNewItems.size());
            }
        } catch (RuntimeException e) {
            log.error(e.getLocalizedMessage());
        }

        // for old orders, update themselves and delete and reinsert their content.
        List<OrderItem> allNewItemsOfOldItems = prepareItems(oldOrders);
        try {
            if (oldOrders.size() != 0) {
                log.info("{} orders to be inserted/updated.", oldOrders.size());
                platformOrderMabangMapper.batchUpdateById(oldOrders);
                platformOrderMabangMapper.batchDeleteByMainID(oldOrders.stream().map(Order::getId).collect(toList()));
            }
            if (ordersFromShippedToCompleted.size() != 0) {
                log.info("{} orders to be updated from Shipped to Completed.", ordersFromShippedToCompleted.size());
                platformOrderMabangMapper.batchUpdateById(ordersFromShippedToCompleted);
                log.info("Contents of {} orders to be updated from Shipped to Completed.", ordersFromShippedToCompleted.size());
                platformOrderMabangMapper.batchUpdateErpStatusByMainId(
                        ordersFromShippedToCompleted.stream().map(Order::getId).collect(toList()),
                        OrderStatus.Completed.getCode());
            }
            if (invoicedShippedOrders.size() != 0) {
                log.info("{} orders to be updated from Pending/Preparing to Shipped.", invoicedShippedOrders.size());
                platformOrderMabangMapper.batchUpdateById(invoicedShippedOrders);
                log.info("Contents of {} orders to be updated from Pending/Preparing to Shipped.", invoicedShippedOrders.size());
                platformOrderMabangMapper.batchUpdateErpStatusByMainId(
                        invoicedShippedOrders.stream().map(Order::getId).collect(toList()),
                        OrderStatus.Shipped.getCode());
            }
            if (obsoleteOrders.size() != 0) {
                log.info("{} orders to become obsolete.", obsoleteOrders.size());
                platformOrderMabangMapper.batchUpdateById(obsoleteOrders);
                log.info("Contents of {} orders to be updated to Obsolete.", obsoleteOrders.size());
                platformOrderMabangMapper.batchUpdateErpStatusByMainId(
                        obsoleteOrders.stream().map(Order::getId).collect(toList()),
                        OrderStatus.Obsolete.getCode());
            }
            if (allNewItemsOfOldItems.size() != 0) {
                log.info("{} order items to be inserted/updated.", allNewItemsOfOldItems.size());
                platformOrderMabangMapper.insertOrderItemsFromMabang(allNewItemsOfOldItems);
            }
        } catch (RuntimeException e) {
            log.error(e.getLocalizedMessage());
        }
    }

    private List<OrderItem> prepareItems(ArrayList<Order> orders) {
        List<OrderItem> orderItems = new ArrayList<>();
        for (Order order : orders) {
            order.resolveStatus();
            order.resolveProductAvailability();
            order.getOrderItems().forEach(
                    item -> {
                        item.setPlatformOrderId(order.getId());
                        item.resolveStatus(order.getStatus());
                        item.resolveProductAvailability();
                    }
            );
            orderItems.addAll(order.getOrderItems());
        }
        return orderItems;
    }

    @Override
    @Transactional
    public void updateMergedOrderFromMabang(Order order, Collection<String> sourceOrderErpId) {
        String targetID = platformOrderMabangMapper.findIdByErpCode(order.getPlatformOrderNumber());
        List<String> sourceIDs = sourceOrderErpId.stream()
                .map(platformOrderMabangMapper::findIdByErpId)
                .collect(toList());

        platformOrderMabangMapper.updateMergedOrder(targetID, sourceIDs);
        platformOrderMabangMapper.updateMergedOrderItems(targetID, sourceIDs);
    }

    @Override
    public Responses suspendOrder(PlatformOrderOperation orderOperation) {
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        List<String> orderIds = Arrays.stream(orderOperation.getOrderIds().split(",")).map(String::trim).collect(toList());
        // group id is the response from mabang API
        Responses responses = new Responses();
        ExecutorService throttlingExecutorService = ThrottlingExecutorService.createExecutorService(DEFAULT_NUMBER_OF_THREADS,
                MABANG_API_RATE_LIMIT_PER_MINUTE, TimeUnit.MINUTES);

        List<CompletableFuture<Responses>> futures =  orderIds.stream()
            .map(id -> CompletableFuture.supplyAsync(() -> {
                OrderSuspendRequestBody body = new OrderSuspendRequestBody(id, sysUser.getRealname() + " : " + orderOperation.getReason());
                OrderSuspendRequest request = new OrderSuspendRequest(body);
                OrderSuspendResponse response = request.send();
                Responses r = new Responses();
                if(response.success())
                    r.addSuccess(id);
                else
                    r.addFailure(id);
                return r;
            }, throttlingExecutorService))
            .collect(toList());
        List<Responses> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(toList());
        results.forEach(r -> {
            responses.getSuccesses().addAll(r.getSuccesses());
            responses.getFailures().addAll(r.getFailures());
        });
        log.info("{}/{} orders suspended successfully.", responses.getSuccesses().size(), orderIds.size());
        return responses;
    }

    @Override
    public Responses cancelOrders(PlatformOrderOperation orderOperation) {
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        List<String> orderIds = Arrays.stream(orderOperation.getOrderIds().split(",")).map(String::trim).collect(toList());

        Responses responses = new Responses();
        ExecutorService throttlingExecutorService = ThrottlingExecutorService.createExecutorService(DEFAULT_NUMBER_OF_THREADS,
                MABANG_API_RATE_LIMIT_PER_MINUTE, TimeUnit.MINUTES);

        List<CompletableFuture<Responses>> futures =  orderIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    ChangeOrderRequestBody body = new ChangeOrderRequestBody(id, "5",null, null, sysUser.getRealname() + " : " + orderOperation.getReason());
                    ChangeOrderRequest request = new ChangeOrderRequest(body);
                    ChangeOrderResponse response = request.send();
                    Responses r = new Responses();
                    if(response.success())
                        r.addSuccess(id);
                    else
                        r.addFailure(id);
                    return r;
                }, throttlingExecutorService))
                .collect(toList());
        List<Responses> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(toList());
        results.forEach(r -> {
            responses.getSuccesses().addAll(r.getSuccesses());
            responses.getFailures().addAll(r.getFailures());
        });
        log.info("{}/{} orders cancelled successfully.", responses.getSuccesses().size(), orderIds.size());
        return responses;
    }

    private void updateExistedOrders(List<Order> orders) {

    }
}
