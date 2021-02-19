/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.permissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.netflix.spinnaker.fiat.config.UnrestrictedResourceConfig;
import com.netflix.spinnaker.fiat.model.UserPermission;
import com.netflix.spinnaker.fiat.model.resources.Resource;
import com.netflix.spinnaker.fiat.model.resources.ResourceType;
import com.netflix.spinnaker.fiat.model.resources.Role;
import com.netflix.spinnaker.kork.exceptions.IntegrationException;
import com.netflix.spinnaker.kork.exceptions.SpinnakerException;
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate;
import io.github.resilience4j.retry.RetryRegistry;
import io.vavr.Tuple3;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
import redis.clients.jedis.*;
import redis.clients.jedis.commands.BinaryJedisCommands;
import redis.clients.jedis.util.SafeEncoder;

/**
 * This Redis-backed permission repository is structured in a way to optimized reading types of
 * resource permissions. In general, this looks like a key schema like: <code>
 * "prefix:myuser@domain.org:resources": {
 * "resourceName1": "[serialized json of resourceName1]",
 * "resourceName2": "[serialized json of resourceName2]"
 * }
 * </code> Additionally, a helper key, called the "all users" key, maintains a set of all usernames.
 *
 * <p>It's important to note that gets and puts are not symmetrical by design. That is, what you put
 * in will likely not be exactly what you get out. That's because of "unrestricted" resources, which
 * are added to the returned UserPermission.
 */
@Slf4j
public class RedisPermissionsRepository implements PermissionsRepository {

  private static final String REDIS_READ_RETRY = "permissionsRepositoryRedisRead";

  private static final String KEY_PERMISSIONS = "permissions";
  private static final String KEY_PERMISSIONS_LZ4 = "permissions-lz4";
  private static final String KEY_ROLES = "roles";
  private static final String KEY_ALL_USERS = "users";
  private static final String KEY_ADMIN = "admin";
  private static final String KEY_LAST_MODIFIED = "last_modified";

  private static final String UNRESTRICTED = UnrestrictedResourceConfig.UNRESTRICTED_USERNAME;
  private static final String NO_LAST_MODIFIED = "unknown_last_modified";

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RedisClientDelegate redisClientDelegate;
  private final List<Resource> resources;
  private final RedisPermissionRepositoryConfigProps configProps;
  private final RetryRegistry retryRegistry;
  private final AtomicReference<String> fallbackLastModified = new AtomicReference<>(null);
  private final LZ4Compressor lz4Compressor;
  private final LZ4SafeDecompressor lz4Decompressor;

  private final LoadingCache<String, UserPermission> unrestrictedPermission =
      Caffeine.newBuilder()
          .expireAfterAccess(Duration.ofSeconds(10))
          .build(this::reloadUnrestricted);

  private final String prefix;
  private final byte[] allUsersKey;
  private final byte[] adminKey;

  RedisPermissionsRepository(
      Clock clock,
      ObjectMapper objectMapper,
      RedisClientDelegate redisClientDelegate,
      List<Resource> resources,
      RedisPermissionRepositoryConfigProps configProps,
      RetryRegistry retryRegistry) {
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.redisClientDelegate = redisClientDelegate;
    this.configProps = configProps;
    this.prefix = configProps.getPrefix();
    this.resources = resources;
    this.retryRegistry = retryRegistry;

    LZ4Factory factory = LZ4Factory.fastestInstance();
    this.lz4Compressor = factory.fastCompressor();
    this.lz4Decompressor = factory.safeDecompressor();

    this.allUsersKey = SafeEncoder.encode(String.format("%s:%s", prefix, KEY_ALL_USERS));
    this.adminKey =
        SafeEncoder.encode(String.format("%s:%s:%s", prefix, KEY_PERMISSIONS, KEY_ADMIN));
  }

  public RedisPermissionsRepository(
      ObjectMapper objectMapper,
      RedisClientDelegate redisClientDelegate,
      List<Resource> resources,
      RedisPermissionRepositoryConfigProps configProps,
      RetryRegistry retryRegistry) {
    this(
        Clock.systemUTC(),
        objectMapper,
        redisClientDelegate,
        resources,
        configProps,
        retryRegistry);
  }

  private UserPermission reloadUnrestricted(String cacheKey) {
    return getFromRedis(UNRESTRICTED)
        .map(
            p -> {
              log.debug("reloaded user {} for key {} as {}", UNRESTRICTED, cacheKey, p);
              return p;
            })
        .orElseThrow(
            () -> {
              log.error(
                  "loading user {} for key {} failed, no permissions returned",
                  UNRESTRICTED,
                  cacheKey);
              return new PermissionRepositoryException("Failed to read unrestricted user");
            });
  }

  private UserPermission getUnrestrictedUserPermission() {
    String serverLastModified = NO_LAST_MODIFIED;
    byte[] bServerLastModified =
        (byte[])
            redisRead(
                new TimeoutContext(
                    "checkLastModified",
                    clock,
                    configProps.getRepository().getCheckLastModifiedTimeout()),
                c -> c.get(SafeEncoder.encode(unrestrictedLastModifiedKey())));
    if (bServerLastModified == null || bServerLastModified.length == 0) {
      log.debug(
          "no last modified time available in redis for user {} using default of {}",
          UNRESTRICTED,
          NO_LAST_MODIFIED);
    } else {
      serverLastModified = SafeEncoder.encode(bServerLastModified);
    }

    try {
      UserPermission userPermission = unrestrictedPermission.get(serverLastModified);
      if (userPermission != null && !serverLastModified.equals(NO_LAST_MODIFIED)) {
        fallbackLastModified.set(serverLastModified);
      }
      return userPermission;
    } catch (Throwable ex) {
      log.error(
          "failed reading user {} from cache for key {}", UNRESTRICTED, serverLastModified, ex);
      String fallback = fallbackLastModified.get();
      if (fallback != null) {
        UserPermission fallbackPermission = unrestrictedPermission.getIfPresent(fallback);
        if (fallbackPermission != null) {
          log.warn(
              "serving fallback permission for user {} from key {} as {}",
              UNRESTRICTED,
              fallback,
              fallbackPermission);
          return fallbackPermission;
        }
        log.warn("no fallback entry remaining in cache for key {}", fallback);
      }
      if (ex instanceof RuntimeException) {
        throw (RuntimeException) ex;
      }
      throw new IntegrationException(ex);
    }
  }

  @Override
  public RedisPermissionsRepository put(@NonNull UserPermission permission) {
    String userId = permission.getId();
    byte[] bUserId = SafeEncoder.encode(userId);
    List<ResourceType> resourceTypes =
        resources.stream().map(Resource::getResourceType).collect(Collectors.toList());
    Map<ResourceType, Map<String, String>> resourceTypeToRedisValue =
        new HashMap<>(resourceTypes.size());

    permission
        .getAllResources()
        .forEach(
            resource -> {
              try {
                resourceTypeToRedisValue
                    .computeIfAbsent(resource.getResourceType(), key -> new HashMap<>())
                    .put(resource.getName(), objectMapper.writeValueAsString(resource));
              } catch (JsonProcessingException jpe) {
                log.error("Serialization exception writing {} entry.", permission.getId(), jpe);
              }
            });

    try {

      TimeoutContext timeoutContext =
          new TimeoutContext(
              String.format("put for user permissions: %s", userId),
              clock,
              configProps.getRepository().getPutTimeout());

      Set<Role> existingRoles =
          getRedisUserResourceMap(timeoutContext, userId, ResourceType.ROLE).values().stream()
              .map(
                  (ThrowingFunction<String, Role>)
                      serialized -> objectMapper.readValue(serialized, Role.class))
              .collect(Collectors.toSet());

      List<Tuple3<ResourceType, byte[], byte[]>> bResources = new ArrayList<>();

      for (ResourceType rt : resourceTypes) {
        Map<String, String> redisValue = resourceTypeToRedisValue.get(rt);
        byte[] userResourceKey = userKey(userId, rt);

        if (redisValue == null || redisValue.size() == 0) {
          bResources.add(new Tuple3<>(rt, userResourceKey, null));
        } else {
          byte[] redisBytes = objectMapper.writeValueAsBytes(redisValue);
          byte[] compressed = lz4Compressor.compress(redisBytes);
          bResources.add(new Tuple3<>(rt, userResourceKey, compressed));
        }
      }

      AtomicReference<Response<List<String>>> serverTime = new AtomicReference<>();
      redisClientDelegate.withMultiKeyPipeline(
          pipeline -> {
            if (permission.isAdmin()) {
              pipeline.sadd(adminKey, bUserId);
            } else {
              pipeline.srem(adminKey, bUserId);
            }

            permission.getRoles().forEach(role -> pipeline.sadd(roleKey(role), bUserId));
            existingRoles.stream()
                .filter(it -> !permission.getRoles().contains(it))
                .forEach(role -> pipeline.srem(roleKey(role), bUserId));

            for (Tuple3<ResourceType, byte[], byte[]> r : bResources) {
              if (r._3 == null) {
                pipeline.del(r._2);
              } else {
                byte[] tempKey = SafeEncoder.encode(UUID.randomUUID().toString());
                pipeline.set(tempKey, r._3);
                pipeline.rename(tempKey, r._2);
              }
            }

            serverTime.set(pipeline.time());
            pipeline.sadd(allUsersKey, bUserId);

            pipeline.sync();
          });
      if (UNRESTRICTED.equals(userId)) {
        String lastModified = serverTime.get().get().get(0);
        redisClientDelegate.withCommandsClient(
            c -> {
              log.debug("set last modified for user {} to {}", UNRESTRICTED, lastModified);
              c.set(unrestrictedLastModifiedKey(), lastModified);
            });
      }
    } catch (Exception e) {
      log.error("Storage exception writing {} entry.", userId, e);
    }
    return this;
  }

  @Override
  public Optional<UserPermission> get(@NonNull String id) {
    if (UNRESTRICTED.equals(id)) {
      return Optional.of(getUnrestrictedUserPermission());
    }
    return getFromRedis(id);
  }

  public Map<String, String> getRedisUserResourceMap(
      TimeoutContext ctx, String id, ResourceType resourceType) {
    byte[] key = userKey(id, resourceType);

    return redisRead(
        ctx,
        (ThrowingFunction<BinaryJedisCommands, Map<String, String>>)
            c -> {
              byte[] v = c.get(key);

              if (v == null) {
                return new HashMap<String, String>();
              }
              byte[] decompressed = lz4Decompressor.decompress(v, 1000000);
              return objectMapper.readValue(
                  decompressed, new TypeReference<Map<String, String>>() {});
            });
  }

  private Optional<UserPermission> getFromRedis(@NonNull String id) {
    try {
      TimeoutContext timeoutContext =
          new TimeoutContext(
              String.format("getPermission for user: %s", id),
              clock,
              configProps.getRepository().getGetPermissionTimeout());
      boolean userExists =
          UNRESTRICTED.equals(id)
              || redisRead(timeoutContext, c -> c.sismember(allUsersKey, SafeEncoder.encode(id)));
      if (!userExists) {
        log.debug("request for user {} not found in redis", id);
        return Optional.empty();
      }
      UserPermission userPermission = new UserPermission().setId(id);

      for (Resource r : resources) {
        ResourceType resourceType = r.getResourceType();
        Map<String, String> resourcePermissions =
            getRedisUserResourceMap(timeoutContext, id, resourceType);

        if (resourcePermissions != null) {
          userPermission.addResources(extractResources(resourceType, resourcePermissions));
        }
      }

      if (!UNRESTRICTED.equals(id)) {
        userPermission.setAdmin(
            redisRead(timeoutContext, c -> c.sismember(adminKey, SafeEncoder.encode(id))));
        userPermission.merge(getUnrestrictedUserPermission());
      }
      return Optional.of(userPermission);
    } catch (Throwable t) {
      String message = String.format("Storage exception reading %s entry.", id);
      log.error(message, t);
      if (t instanceof SpinnakerException) {
        throw (SpinnakerException) t;
      }
      throw new PermissionReadException(message, t);
    }
  }

  @Override
  public Map<String, UserPermission> getAllById() {
    Set<String> allUsers =
        scanSet(allUsersKey).stream().map(String::toLowerCase).collect(Collectors.toSet());

    if (allUsers.isEmpty()) {
      return new HashMap<>(0);
    }

    return allUsers.stream()
        .map(this::get)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toMap(UserPermission::getId, p -> p));
  }

  @Override
  public Map<String, UserPermission> getAllByRoles(List<String> anyRoles) {
    if (anyRoles == null) {
      return getAllById();
    } else if (anyRoles.isEmpty()) {
      val unrestricted = getFromRedis(UNRESTRICTED);
      if (unrestricted.isPresent()) {
        val map = new HashMap<String, UserPermission>();
        map.put(UNRESTRICTED, unrestricted.get());
        return map;
      }
      return new HashMap<>();
    }

    final Set<String> uniqueUsernames = new HashSet<>();
    for (String role : new HashSet<>(anyRoles)) {
      uniqueUsernames.addAll(
          scanSet(roleKey(role)).stream().map(String::toLowerCase).collect(Collectors.toSet()));
    }
    uniqueUsernames.add(UNRESTRICTED);

    return uniqueUsernames.stream()
        .map(this::get)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toMap(UserPermission::getId, p -> p));
  }

  @Override
  public void remove(@NonNull String id) {
    try {
      TimeoutContext timeoutContext =
          new TimeoutContext(
              String.format("remove for user: %s", id),
              clock,
              configProps.getRepository().getRemoveTimeout());

      Map<String, String> userRolesById =
          getRedisUserResourceMap(timeoutContext, id, ResourceType.ROLE);
      byte[] bId = SafeEncoder.encode(id);

      redisClientDelegate.withMultiKeyPipeline(
          p -> {
            p.srem(allUsersKey, bId);
            userRolesById.keySet().forEach(roleName -> p.srem(roleKey(roleName), bId));

            resources.stream().map(Resource::getResourceType).forEach(r -> p.del(userKey(id, r)));
            p.srem(adminKey, bId);
            p.sync();
          });
    } catch (Exception e) {
      log.error("Storage exception reading " + id + " entry.", e);
    }
  }

  private Set<String> scanSet(byte[] key) {
    final Set<String> results = new HashSet<>();
    final AtomicReference<byte[]> cursor =
        new AtomicReference<>(ScanParams.SCAN_POINTER_START_BINARY);
    do {
      final ScanResult<byte[]> result =
          redisClientDelegate.withBinaryClient(
              jedis -> {
                return jedis.sscan(key, cursor.get());
              });
      results.addAll(
          result.getResult().stream().map(SafeEncoder::encode).collect(Collectors.toList()));
      cursor.set(result.getCursorAsBytes());
    } while (!Arrays.equals(cursor.get(), ScanParams.SCAN_POINTER_START_BINARY));
    return results;
  }

  private byte[] userKey(String userId, ResourceType r) {
    return SafeEncoder.encode(
        String.format("%s:%s:%s:%s", prefix, KEY_PERMISSIONS_LZ4, userId, r.keySuffix()));
  }

  private byte[] roleKey(Role role) {
    return roleKey(role.getName());
  }

  private byte[] roleKey(String role) {
    return SafeEncoder.encode(String.format("%s:%s:%s", prefix, KEY_ROLES, role));
  }

  private String lastModifiedKey(String userId) {
    return String.format("%s:%s:%s", prefix, KEY_LAST_MODIFIED, userId);
  }

  private String unrestrictedLastModifiedKey() {
    return lastModifiedKey(UNRESTRICTED);
  }

  private Set<Resource> extractResources(ResourceType r, Map<String, String> resourceMap) {
    val modelClazz =
        resources.stream()
            .filter(resource -> resource.getResourceType().equals(r))
            .findFirst()
            .orElseThrow(IllegalArgumentException::new)
            .getClass();
    return resourceMap.values().stream()
        .map(
            (ThrowingFunction<String, ? extends Resource>)
                serialized -> objectMapper.readValue(serialized, modelClazz))
        .collect(Collectors.toSet());
  }

  /** Used to swallow checked exceptions from Jackson methods. */
  @FunctionalInterface
  private interface ThrowingFunction<T, R> extends Function<T, R> {

    @Override
    default R apply(T t) {
      try {
        return applyThrows(t);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    R applyThrows(T t) throws Exception;
  }

  /**
   * TimeoutContext allows specifying an expiration time for request processing.
   *
   * <p>If something exceeds the specified timeout duration, the request handler should stop doing
   * work and just bail out with a timeout.
   */
  private static class TimeoutContext {
    private final String name;
    private final Instant expiry;
    private final Clock clock;
    private final Duration timeout;

    public TimeoutContext(String name, Clock clock, Duration timeout) {
      this(name, clock, timeout, Instant.now(clock));
    }

    public TimeoutContext(String name, Clock clock, Duration timeout, Instant startTime) {
      this.name = name;
      this.expiry = startTime.plus(timeout);
      this.clock = clock;
      this.timeout = timeout;
    }

    boolean isTimedOut() {
      return Instant.now(clock).isAfter(expiry);
    }

    String getName() {
      return name;
    }

    Duration getTimeout() {
      return timeout;
    }
  }

  private <T> T redisRead(TimeoutContext timeoutContext, Function<BinaryJedisCommands, T> fn) {
    return retryRegistry
        .retry(REDIS_READ_RETRY)
        .executeSupplier(
            () -> {
              if (timeoutContext.isTimedOut()) {
                throw new PermissionReadException(
                    String.format(
                        "request processing timeout after %s for %s",
                        timeoutContext.getTimeout(), timeoutContext.getName()));
              }
              return redisClientDelegate.withBinaryClient(fn);
            });
  }
}
