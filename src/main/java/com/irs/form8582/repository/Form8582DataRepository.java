package com.irs.form8582.repository;

import com.irs.form8582.model.Form8582Data;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface Form8582DataRepository extends JpaRepository<Form8582Data, Long> {

    Optional<Form8582Data> findByTaxpayerIdAndTaxYear(String taxpayerId, Integer taxYear);

    @Query("SELECT f FROM Form8582Data f WHERE f.taxpayerId = :taxpayerId AND f.taxYear = :taxYear")
    Optional<Form8582Data> findFormData(@Param("taxpayerId") String taxpayerId, @Param("taxYear") Integer taxYear);
}