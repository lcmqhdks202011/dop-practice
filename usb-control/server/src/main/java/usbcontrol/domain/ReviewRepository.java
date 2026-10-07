package usbcontrol.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findAllByOrderByIdDesc();

    Optional<Review> findFirstByOrderByReviewedAtDesc();
}
