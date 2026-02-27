package modernconstructors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

public class NonCanonicalConstructor {

    private static final Logger log = LoggerFactory.getLogger(NonCanonicalConstructor.class);


    //NC constructor에 대해서는 not all components are required when we construct
    record Task (String title,
                 LocalDate createAt
                 ){

        Task(String title){ // LocalDate.now becomes its default value,
            // this will apply only when it's provided with only title.
            this(title, LocalDate.now());
        }

    }


    static void main() {

        var task = new Task("Submit report", LocalDate.now());

        log.info("{}", task);

        var task2 = new Task("Submit report");

        log.info("{}", task2);
    }
}
