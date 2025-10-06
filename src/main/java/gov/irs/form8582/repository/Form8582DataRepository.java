package gov.irs.form8582.repository;

import gov.irs.form8582.model.Form8582Data;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface Form8582DataRepository extends JpaRepository<Form8582Data, Long> {

    Optional<Form8582Data> findByTaxpayerIdATaaTdaxYear(String taxpayerId, Integer taxYear);
}