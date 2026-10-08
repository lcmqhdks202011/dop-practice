package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PiFindingRepository extends JpaRepository<PiFinding, Long> {

    List<PiFinding> findByScanIdOrderByIdAsc(Long scanId);

    List<PiFinding> findByScanIdInOrderByPcNameAscIdAsc(Collection<Long> scanIds);
}
