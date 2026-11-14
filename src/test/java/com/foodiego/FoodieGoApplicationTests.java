package com.foodiego;

import com.foodiego.entity.Shop;
import com.foodiego.service.IShopService;
import com.foodiego.service.impl.ShopServiceImpl;
import com.foodiego.utils.CacheClient;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.RedisIdWorker;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
@Disabled("Manual local data-loading utilities; run explicitly with local services when needed.")
class FoodieGoApplicationTests {

    @Resource
    private CacheClient cacheClient;
    @Autowired
    private ShopServiceImpl shopServiceImpl;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IShopService shopService;

    // 一键跑所有测试
    // mvn clean test -Dtest="CacheClientTest,RedisIdWorkerTest,RabbitMQHandlerTest,VoucherOrderMQServiceImplTest,ShopServiceImplTest,UserServiceImplTest,BlogServiceImplTest,FollowServiceImplTest" -DfailIfNoTests=false
//    @Test
//    void saveShopToRedis(){
//        Shop shop = shopServiceImpl.getById(1L);
//
//        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, shop, 10L, TimeUnit.SECONDS);
//    }
//    @Test
//    void getTimeStamp(){
//        LocalDateTime localDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
//        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second="+ second);
//    }
    private ExecutorService ex = Executors.newFixedThreadPool(500);
    @Test
    void testRedisIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            ex.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }

    @Test
    void loadShopGEO(){
        // 获取所有店铺
        List<Shop> shopList = shopService.list();
        // 根据typeId把所有店铺分组
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 获取不同类型店铺集合
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<Shop> list = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(list.size());
            for (Shop shop : list) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }

            // 存储
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
}
