package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllowedDeviceRepository extends JpaRepository<AllowedDevice, Long> {

    List<AllowedDevice> findAllByOrderByIdDesc();

    List<AllowedDevice> findByRevokedFalse();
}
