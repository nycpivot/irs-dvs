package com.irs.form8582.repository;

import com.irs.form8582.model.PassiveActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PassiveActivityRepository extends JpaRepository<PassiveActivity, Long> {

    List<PassiveActivity> findByTaxYear(Integer taxYear);

    List<PassiveActivity> findByActivityType(String activityType);

    @Query("SELECT p FROM PassiveActivity p WHERE p.taxYear = :taxYear AND p.activeParticipation = true")
    List<PassiveActivity> findActiveParticipationActivities(@Param("taxYear") Integer taxYear);
}