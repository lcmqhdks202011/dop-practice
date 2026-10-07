package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PcRepository extends JpaRepository<Pc, Long> {

    Optional<Pc> findByNameIgnoreCase(String name);

    List<Pc> findAllByOrderByNameAsc();
}
