import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class OrderEntity {

    @Column(name = "customer_email")
    private String email;

    @Column(nullable = true)
    private String notes;
}
