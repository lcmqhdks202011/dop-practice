package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UsbEventRepository extends JpaRepository<UsbEvent, Long> {

    List<UsbEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(LocalDateTime from, LocalDateTime to);

    List<UsbEvent> findTop10ByActionStartingWithOrderByOccurredAtDesc(String actionPrefix);

    List<UsbEvent> findTop10ByKindInAndActionInOrderByOccurredAtDesc(List<String> kinds, List<String> actions);

    List<UsbEvent> findTop10ByPrivacyPcTrueAndActionInOrderByOccurredAtDesc(List<String> actions);
}
