package telegram.files.repository.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.templates.SqlTemplate;
import telegram.files.Config;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingRecord;
import telegram.files.repository.SettingRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SettingRepositoryImpl extends AbstractSqlRepository implements SettingRepository {

    private static final Log log = LogFactory.get();

    public SettingRepositoryImpl(SqlClient sqlClient) {
        super(sqlClient);
    }

    @Override
    public Future<SettingRecord> createOrUpdate(String key, String value) {
        return SqlTemplate
                .forUpdate(sqlClient, Config.isMysql() ?
                        """
                                INSERT INTO setting_record(`key`, value) VALUES (#{key}, #{value})
                                ON DUPLICATE KEY UPDATE value = VALUES(value)""" :
                        """
                                INSERT INTO setting_record(key, value) VALUES (#{key}, #{value})
                                ON CONFLICT (key) DO UPDATE SET value = #{value}""")
                .mapFrom(SettingRecord.PARAM_MAPPER)
                .execute(new SettingRecord(key, value))
                .map(r -> new SettingRecord(key, value))
                .onSuccess(r -> log.trace("Successfully created or updated setting record: %s".formatted(key)))
                .onFailure(
                        err -> log.error("Failed to create or update setting record: %s".formatted(err.getMessage()))
                );
    }

    @Override
    public Future<List<SettingRecord>> getByKeys(List<String> keys) {
        if (CollUtil.isEmpty(keys)) {
            return Future.succeededFuture(List.of());
        }
        String keyStr = keys.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .map(key -> StrUtil.wrap(key, "'"))
                .collect(Collectors.joining(","));

        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT %s, value FROM setting_record WHERE %s IN (%s)
                        """.formatted(SettingRecord.KEY_FIELD, SettingRecord.KEY_FIELD, keyStr))
                .mapTo(SettingRecord.ROW_MAPPER)
                .execute(Collections.emptyMap())
                .map(IterUtil::toList)
                .onSuccess(r -> log.trace("Successfully fetched setting record for keys: " + keyStr))
                .onFailure(
                        err -> log.error("Failed to fetch setting record: %s".formatted(err.getMessage()))
                );
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Future<T> getByKey(SettingKey key) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT value FROM setting_record WHERE %s = #{key}
                        """.formatted(SettingRecord.KEY_FIELD))
                .mapTo(row -> row.getString("value"))
                .execute(Map.of("key", key.name()))
                .map(rs -> {
                    if (rs.size() == 1) {
                        return (T) key.converter.apply(rs.iterator().next());
                    }
                    return key.defaultValue == null ? null : (T) key.defaultValue;
                })
                .onSuccess(r -> log.trace("Successfully fetched setting record for key: " + key))
                .onFailure(
                        err -> log.error("Failed to fetch setting record: %s".formatted(err.getMessage()))
                );
    }
}
