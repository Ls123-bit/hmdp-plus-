package org.javaup.controller;


import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.CancelVoucherOrderDto;
import org.javaup.dto.GetVoucherOrderByVoucherIdDto;
import org.javaup.dto.GetVoucherOrderDto;
import org.javaup.dto.Result;
import org.javaup.execute.RateLimitHandler;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.javaup.service.IReconciliationTaskService;
import org.javaup.service.ISeckillAccessTokenService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券订单api
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillAccessTokenService accessTokenService;

    @Resource
    private RateLimitHandler rateLimitHandler;
    
    @Resource
    private IReconciliationTaskService reconciliationTaskService;

    @GetMapping("/seckill/token/{id}")
    public Result<String> issueSeckillAccessToken(@PathVariable("id") Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //分层限流：在令牌发放之前进行按IP与用户的速率限制（申请权限令牌的时候，进行限流，防止用户大量的申请权限令牌，）  申请权限令牌之前也要进行限流 限流里面支持我们的滑动窗口和我们的令牌桶
        rateLimitHandler.execute(voucherId, userId, RateLimitScene.ISSUE_TOKEN);
        //先根据优惠券id和用户id去申请一个token访问令牌（一次不复用的通行证）  更像一种权限空值==控制，有这个票据才有资格去看优惠券详情
        String token = accessTokenService.issueAccessToken(voucherId, userId);
        return Result.ok(token);
    }
//申请到令牌之后，在下单里面去验证这个令牌。
    @PostMapping("/seckill/{id}")
    public Result<Long> seckillVoucher(@PathVariable("id") Long voucherId,
                                       @RequestParam(name = "accessToken", required = false) String accessToken) {
        Long userId = UserHolder.getUser().getId();
       //分层限流，在令牌校验下/下单前进行限流，防止过载，（防刷订单）
        rateLimitHandler.execute(voucherId, userId, RateLimitScene.SECKILL_ORDER);
        //如果激活了这个令牌之后，才校验令牌是不是有效的   令牌是资格，     令牌桶是速率，更像一个桶 取水，一边生成水，（面向流量的配额机制，用来在时间维度上平滑请求速率，吸引突发流量，避免系统被瞬时冲击）
        if (accessTokenService.isEnabled()) {
            if (accessToken == null || !accessTokenService.validateAndConsume(voucherId, userId, accessToken)) { //如果令牌是空的话，或者令牌校验失败，令牌已失效，都返回失败
                return Result.fail("令牌校验失败或令牌已失效");
            }
        }  //验证通过之后才执行我们的下单    如果通过的话就返回下单接口service
        return voucherOrderService.seckillVoucher(voucherId);
    }
    //根据订单id查询秒杀订单
    @PostMapping("/get/seckill/voucher/order-id")
    public Result<Long> getSeckillVoucherOrder(@Valid @RequestBody GetVoucherOrderDto getVoucherOrderDto) {
        return Result.ok(voucherOrderService.getSeckillVoucherOrder(getVoucherOrderDto));
    }
    //根据优惠券id查询秒杀订单
    @PostMapping("/get/seckill/voucher/order-id/by/voucher-id")
    public Result<Long> getSeckillVoucherOrderIdByVoucherId(@Valid @RequestBody GetVoucherOrderByVoucherIdDto getVoucherOrderByVoucherIdDto) {
        return Result.ok(voucherOrderService.getSeckillVoucherOrderIdByVoucherId(getVoucherOrderByVoucherIdDto));
    }
    //取消订单
    @PostMapping("/cancel")
    public Result<Boolean> cancel(@Valid @RequestBody CancelVoucherOrderDto cancelVoucherOrderDto) {
        return Result.ok(voucherOrderService.cancel(cancelVoucherOrderDto));
    }
    //执行订单对账任务
    @PostMapping(value = "/reconciliation/task/all")
    public Result<Void> reconciliationTaskAll() {
        reconciliationTaskService.reconciliationTaskExecute();
        return Result.ok();
    }
}
