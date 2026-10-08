package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiScanRepository extends JpaRepository<PiScan, Long> {

    List<PiScan> findAllByOrderByFinishedAtDescIdDesc();

    List<PiScan> findByPcNameIgnoreCaseOrderByFinishedAtDescIdDesc(String pcName);
}
