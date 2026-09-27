package com.vietmart.product;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;

// ==========================================
// 1. MAIN APPLICATION ENTRY POINT
// ==========================================
@SpringBootApplication
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}

// ==========================================
// 2. CONFIGURATION & FALLBACK HANDLING
// ==========================================
@Configuration
@EnableCaching
@Slf4j
class RedisConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10)) // TTL 10 phút
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * Fallback mechanism: Khi Redis sập, ghi log lỗi và để ứng dụng 
     * tiếp tục truy vấn trực tiếp vào Database mà không làm sập API.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CustomCacheErrorHandler();
    }

    private static class CustomCacheErrorHandler implements CacheErrorHandler {
        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.error("Redis GET connection failure for key: {}. Fallback to DB. Error: {}", key, exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.error("Redis PUT connection failure for key: {}. Error: {}", key, exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.error("Redis EVICT connection failure for key: {}. Error: {}", key, exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.error("Redis CLEAR connection failure. Error: {}", exception.getMessage());
        }
    }
}

// ==========================================
// 3. DOMAIN ENTITY
// ==========================================
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class Product implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private BigDecimal price;

    private Integer stock;
}

// ==========================================
// 4. REPOSITORY
// ==========================================
interface ProductRepository extends JpaRepository<Product, Long> {}

// ==========================================
// 5. SERVICE LAYER (Cache-Aside Pattern)
// ==========================================
@Service
@RequiredArgsConstructor
@Slf4j
class ProductService {

    private final ProductRepository productRepository;

    /**
     * READ FLOW:
     * - Kiểm tra Cache -> Nếu miss thì vào DB lấy -> Nạp vào Cache -> Trả về.
     * - sync = true: Chống Cache Stampede (xử lý đồng thời nhiều request cùng truy cập 1 key bị cold start).
     */
    @Cacheable(value = "products", key = "#id", sync = true)
    public Product getProductById(Long id) {
        log.info("Cache Miss! Querying database for Product ID: {}", id);
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + id));
    }

    /**
     * WRITE FLOW & DATA CONSISTENCY:
     * - Cập nhật DB trước.
     * - Xóa Cache sau bằng @CacheEvict để tránh Race Condition (Không dùng @CachePut).
     */
    @Transactional
    @CacheEvict(value = "products", key = "#product.id")
    public Product updateProduct(Product product) {
        log.info("Updating DB for Product ID: {} and evicting cache key", product.getId());
        Product existingProduct = productRepository.findById(product.getId())
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + product.getId()));

        existingProduct.setName(product.getName());
        existingProduct.setPrice(product.getPrice());
        existingProduct.setStock(product.getStock());

        return productRepository.save(existingProduct);
    }
}

// ==========================================
// 6. REST CONTROLLER
// ==========================================
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
class ProductController {

    private final ProductService productService;

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return ResponseEntity.ok(productService.updateProduct(product));
    }
}

/*
================================================================================
FILE CONFIGURATION: application.yml
================================================================================
spring:
  application:
    name: product-service
  datasource:
    url: jdbc:mysql://localhost:3306/vietmart_db?useSSL=false&serverTimezone=UTC
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
  cache:
    type: redis

================================================================================
FILE: README.md
================================================================================
# Product Service - Cache-Aside Pattern with Redis

## 1. Hướng dẫn chạy Docker Redis
Sử dụng câu lệnh sau để khởi chạy Redis container:
```bash
docker run -d --name vietmart-redis -p 6379:6379 redis:alpine
```

## 2. Kiểm tra Cache Hit / Cache Miss & Fallback
1. **Lần gọi đầu tiên GET `/api/products/1`**: Thời gian phản hồi ~100-200ms (Cache Miss - Truy vấn DB).
2. **Các lần gọi tiếp theo GET `/api/products/1`**: Thời gian phản hồi < 50ms (Cache Hit - Lấy từ Redis).
3. **Cập nhật dữ liệu PUT `/api/products/1`**: Dữ liệu DB được cập nhật, key `products::1` trong Redis bị xửa bỏ (@CacheEvict).
4. **Gọi lại GET `/api/products/1`**: Trả về dữ liệu mới nhất.
5. **Fallback Test**: Stop Redis container (`docker stop vietmart-redis`), API vẫn hoạt động bình thường bằng cách fallback trực tiếp xuống DB.
*/