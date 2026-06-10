package com.wuwei.repo;

import com.wuwei.entity.ModelRoutingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ModelRoutingRepo extends JpaRepository<ModelRoutingEntity, String> {

    @Modifying
    @Transactional
    @Query("UPDATE ModelRoutingEntity m SET m.provider = :provider, m.model = :model, " +
           "m.apiUrl = :apiUrl, m.params = :params " +
           "WHERE m.apiKey = '' OR m.apiKey IS NULL")
    int updateDefaults(@Param("provider") String provider,
                       @Param("model") String model,
                       @Param("apiUrl") String apiUrl,
                       @Param("params") String params);
}
