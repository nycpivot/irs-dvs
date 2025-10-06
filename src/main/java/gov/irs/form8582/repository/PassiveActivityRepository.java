package gov.irs.form8582.repository;

import gov.irs.form8582.model.PassiveActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PassiveActivityRepository extends JpaRepository<PassiveActivity, Long> {

    List<PassiveActivity> findByTaxYear(Integer taxYear);

    List<PassiveActivity> findByActivityType(String activityType);

    List<PassiveActivity> findByActiveParticipationTrue();

    List<PassiveActivity> findByIsPubliclyTradedPartnershipTrue();
}