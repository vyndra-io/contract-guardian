import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class UserEntity {

    @Column(name = "email_address")
    private String email;
}
