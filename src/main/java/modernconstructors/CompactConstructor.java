package modernconstructors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


// 컴포넌트 개수가 많은데 수정할게 별로 없는 경우.
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
            // 제약조건/밸리데이션을 추가 할 수도 있다.
            Objects.requireNonNull(lastName, "lastName cannot be null");
            lastName = lastName.toUpperCase();
            // 나머진 필드 설정할 필요는 없음 위에처럼 처리 필요한 녀석만 따로 작성

            // 설정이 끝나면 나머지 컴포넌트들이 초기화 된다.

        }



    }

    static void main() {
        var person1 = new Person("Kim", "Jun", "32 Nambu", "notanemail@notemail.com", 55);

        // 모든 필드 다 들어있는지, lastName은 대문자인지 확인
        log.info("{}", person1);
        // EXECUTION
        //05:22:36.358 INFO  {           main} } : Person[firstName=Kim, lastName=JUN, address=32 Nambu, email=notanemail@notemail.com, age=55]

        var person2 = new Person("Kim", null, "32 Nambu", "notanemail@notemail.com", 55);

        //EXECUTION
        /*
        Exception in thread "main" java.lang.NullPointerException: lastName cannot be null
        at java.base/java.util.Objects.requireNonNull(Objects.java:246)
        at modernconstructors.CompactConstructor$Person.<init>(CompactConstructor.java:25)
        at modernconstructors.CompactConstructor.main(CompactConstructor.java:42)*/


    }
}
