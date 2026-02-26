package modernconstructors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompactConstructor {


    private static final Logger log = LoggerFactory.getLogger(CanonicalConstructor.class);

    // use case : component 개수가 많을때 쓴다
    record Person(String firstName,
                  String lastName,
                  String address,
                  String email,
                  int age
                  ){

        Person {
            lastName = lastName.toUpperCase();
            // 나머진 필드 설정할 필요는 없음 위에처럼 처리 필요한 녀석만 따로 작성
        }



    }

    static void main() {
        var person1 = new Person("Kim", "Jun", "32 Nambu", "notanemail@notemail.com", 55);

        // 모든 필드 다 들어있는지, lastName은 대문자인지 확인
        log.info("{}", person1);
        // EXECUTION
        //05:22:36.358 INFO  {           main} } : Person[firstName=Kim, lastName=JUN, address=32 Nambu, email=notanemail@notemail.com, age=55]
    }
}
