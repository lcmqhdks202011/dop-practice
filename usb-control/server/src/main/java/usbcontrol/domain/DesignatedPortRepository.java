package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DesignatedPortRepository extends JpaRepository<DesignatedPort, Long> {

    List<DesignatedPort> findByPcNameIgnoreCaseOrderByKindDescIdAsc(String pcName);
}
