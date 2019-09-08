package me.zhengjie.modules.mall.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import me.zhengjie.domain.EmailConfig;
import me.zhengjie.domain.Log;
import me.zhengjie.domain.vo.EmailVo;
import me.zhengjie.modules.mall.domain.GoodsMonitor;
import me.zhengjie.modules.mall.repository.GoodsMonitorRepository;
import me.zhengjie.modules.mall.service.GoodsMonitorService;
import me.zhengjie.modules.mall.service.dto.GoodsMonitorDTO;
import me.zhengjie.modules.mall.service.dto.GoodsMonitorQueryCriteria;
import me.zhengjie.modules.mall.service.mapper.GoodsMonitorMapper;
import me.zhengjie.repository.LogRepository;
import me.zhengjie.service.EmailService;
import me.zhengjie.service.LogService;
import me.zhengjie.service.impl.LogServiceImpl;
import me.zhengjie.utils.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
* @author masterJ
* @date 2019-09-06
*/
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true, rollbackFor = Exception.class)
public class GoodsMonitorServiceImpl implements GoodsMonitorService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private GoodsMonitorRepository goodsMonitorRepository;

    @Autowired
    private GoodsMonitorMapper goodsMonitorMapper;

    @Autowired
    private EmailService emailService;

    @Autowired
    private LogRepository logRepository;

    @Override
    public Object queryAll(GoodsMonitorQueryCriteria criteria, Pageable pageable){
        Page<GoodsMonitor> page = goodsMonitorRepository.findAll((root, criteriaQuery, criteriaBuilder) -> QueryHelp.getPredicate(root,criteria,criteriaBuilder),pageable);
        return PageUtil.toPage(page.map(goodsMonitorMapper::toDto));
    }

    @Override
    public List<GoodsMonitorDTO> queryAll(GoodsMonitorQueryCriteria criteria){
        return goodsMonitorMapper.toDto(goodsMonitorRepository.findAll((root, criteriaQuery, criteriaBuilder) -> QueryHelp.getPredicate(root,criteria,criteriaBuilder)));
    }

    @Override
    public GoodsMonitorDTO findById(Long id) {
        Optional<GoodsMonitor> goodsMonitor = goodsMonitorRepository.findById(id);
        ValidationUtil.isNull(goodsMonitor,"GoodsMonitor","id",id);
        return goodsMonitorMapper.toDto(goodsMonitor.get());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GoodsMonitorDTO create(GoodsMonitor resources) {
        resources.setCreateBy(SecurityUtils.getUsername());
        resources.setCreateDate(new Timestamp(System.currentTimeMillis()));
        return goodsMonitorMapper.toDto(goodsMonitorRepository.save(resources));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(GoodsMonitor resources) {
        Optional<GoodsMonitor> optionalGoodsMonitor = goodsMonitorRepository.findById(resources.getId());
        ValidationUtil.isNull( optionalGoodsMonitor,"GoodsMonitor","id",resources.getId());
        GoodsMonitor goodsMonitor = optionalGoodsMonitor.get();
        goodsMonitor.copy(resources);
        goodsMonitorRepository.save(goodsMonitor);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        goodsMonitorRepository.deleteById(id);
    }

    @Override
    public GoodsMonitor getGoodsInfo(GoodsMonitor goodsMonitor) {
        ResponseEntity<String> responseEntity = restTemplate.getForEntity(goodsMonitor.getLink(), String.class);
        String body = responseEntity.getBody();

        //<p class="pib-title-detail" title="周大福 车花镂空足金镯子儿童黄金手镯">周大福 车花镂空足金镯子儿童黄金手镯</p>
        Matcher titleMatcher = PatternUtil.VIP_PATTERN_TITLE.matcher(body);
        if (titleMatcher.find()){
            System.out.println("matcher1.group(2) = " + titleMatcher.group(2));
            goodsMonitor.setTitle(titleMatcher.group(2));
        }

        //<a href="//a.vpimg3.com/upload/merchandise/pdcvis/2019/07/15/7/e3e8d3eb-9124-4d8c-a2c0-89fa8dcf6bbe.jpg" class="J-mer-bigImgZoom" rel="undefined" style="outline-style: none; text-decoration: none;" title="">
        Matcher imageMatcher = PatternUtil.VIP_PATTERN_IMAGE.matcher(body);
        if (imageMatcher.find()){
            System.out.println("matcher1.group(2) = " + imageMatcher.group(2));
            goodsMonitor.setImgUrl(imageMatcher.group(2));
        }
        goodsMonitor.setOriginMall("唯品会");
        return goodsMonitor;
    }

    @Override
    public void monitorGoodsPrice() {
        GoodsMonitorQueryCriteria goodsMonitorQueryCriteria = GoodsMonitorQueryCriteria.builder().deleteStatus(false).openStatus(true).build();
        List<GoodsMonitorDTO> goodsMonitorDTOS = this.queryAll(goodsMonitorQueryCriteria);
        System.out.println("goodsMonitorDTOS = " + goodsMonitorDTOS);
        goodsMonitorDTOS.forEach(goodsMonitorDTO -> {
            ResponseEntity<String> responseEntity = restTemplate.getForEntity(goodsMonitorDTO.getLink(), String.class);
            String body = responseEntity.getBody();
            //通过正则来获取 商品的标题，图片地址，价格
            Matcher priceMatcher = PatternUtil.VIP_PATTERN_PRICE.matcher(body);
            if (priceMatcher.find()){
                BigDecimal price = new BigDecimal(priceMatcher.group(2));
                if (price.compareTo(goodsMonitorDTO.getMinPrice()) == -1){
                    //发送降价邮件
                    EmailConfig emailConfig = emailService.find();
                    //封装发送邮件对象
                    EmailVo emailVo = EmailVo.builder().ccs(Arrays.asList(emailConfig.getFromUser())).tos(Arrays.asList(goodsMonitorDTO.getEmail()))
                            .subject(String.format("你关注的商品《%s》降价了",goodsMonitorDTO.getTitle()))
                            .content(String.format("你关注的商品《%s》降价了, 现价：%s， 低于监控价：%s， 请及时购买！链接：%s",goodsMonitorDTO.getTitle(),price,goodsMonitorDTO.getMinPrice(),goodsMonitorDTO.getLink())).build();
                    try {
                        //发送邮件，有可能会失败，失败就保存日志记录
                        emailService.send(emailVo,emailConfig);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log log = new Log();
                        try {
                            String ip = InetAddress.getLocalHost().getHostAddress();
                            log.setLogType("ERROR").setAddress(StringUtils.getCityInfo(ip)).setRequestIp(ip)
                                    .setDescription(String.format("您监控的商品《%s》, 已经降价到最低监控价格，但发送邮件时出现异常", goodsMonitorDTO.getTitle()))
                                    .setExceptionDetail(ThrowableUtil.getStackTrace(e).getBytes())
                                    .setMethod(this.getClass().getName() + ".monitorGoodsPrice()")
                                    .setParams(JSONObject.toJSONString(emailVo))
                                    .setUsername("定时任务->发送邮件");
                        } catch (UnknownHostException ex) {
                            ex.printStackTrace();
                        }
                        logRepository.save(log);
                    }
                }else if(goodsMonitorDTO.getMaxPrice() != null && price.compareTo(goodsMonitorDTO.getMaxPrice()) == 1){
                    //发送涨价邮件
                    EmailConfig emailConfig = emailService.find();
                    //封装发送邮件对象
                    EmailVo emailVo = EmailVo.builder().ccs(Arrays.asList(emailConfig.getFromUser())).tos(Arrays.asList(goodsMonitorDTO.getEmail()))
                            .subject(String.format("你关注的商品《%s》涨价了",goodsMonitorDTO.getTitle()))
                            .content(String.format("你关注的商品《%s》涨价了, 现价：%s， 高于监控价：%s， 真为了感到悲伤！链接：%s",goodsMonitorDTO.getTitle(),price,goodsMonitorDTO.getMinPrice(),goodsMonitorDTO.getLink())).build();
                    try {
                        //发送邮件，有可能会失败，失败就保存日志记录
                        emailService.send(emailVo,emailConfig);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log log = new Log();
                        try {
                            String ip = InetAddress.getLocalHost().getHostAddress();
                            log.setLogType("ERROR").setAddress(StringUtils.getCityInfo(ip)).setRequestIp(ip)
                                    .setDescription(String.format("您监控的商品《%s》, 已经涨价到最高监控价格，但发送邮件时出现异常", goodsMonitorDTO.getTitle()))
                                    .setExceptionDetail(ThrowableUtil.getStackTrace(e).getBytes())
                                    .setMethod(this.getClass().getName() + ".monitorGoodsPrice()")
                                    .setParams(JSONObject.toJSONString(emailVo))
                                    .setUsername("定时任务->发送邮件");
                        } catch (UnknownHostException ex) {
                            ex.printStackTrace();
                        }
                        logRepository.save(log);
                    }
                }
            }
        });
    }


}