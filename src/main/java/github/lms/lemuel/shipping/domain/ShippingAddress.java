package github.lms.lemuel.shipping.domain;

import java.time.LocalDateTime;

/**
 * 배송지 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class ShippingAddress {

    private Long id;
    private Long userId;
    private String recipientName;
    private String phone;
    private String zipCode;
    private String address;
    private String addressDetail;
    private boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ShippingAddress() {}

    public static ShippingAddress create(Long userId, String recipientName, String phone,
                                         String zipCode, String address, String addressDetail) {
        validateRequired(recipientName, phone, zipCode, address);
        ShippingAddress sa = new ShippingAddress();
        sa.userId        = userId;
        sa.recipientName = recipientName;
        sa.phone         = phone;
        sa.zipCode       = zipCode;
        sa.address       = address;
        sa.addressDetail = addressDetail;
        sa.isDefault     = false;
        sa.createdAt     = LocalDateTime.now();
        sa.updatedAt     = LocalDateTime.now();
        return sa;
    }

    public void setAsDefault() {
        this.isDefault = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void unsetDefault() {
        this.isDefault = false;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateInfo(String recipientName, String phone, String zipCode,
                           String address, String addressDetail) {
        validateRequired(recipientName, phone, zipCode, address);
        this.recipientName = recipientName;
        this.phone         = phone;
        this.zipCode       = zipCode;
        this.address       = address;
        this.addressDetail = addressDetail;
        this.updatedAt     = LocalDateTime.now();
    }

    private static void validateRequired(String recipientName, String phone,
                                          String zipCode, String address) {
        if (recipientName == null || recipientName.isBlank()) {
            throw new IllegalArgumentException("수령인 이름은 필수입니다.");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("전화번호는 필수입니다.");
        }
        if (zipCode == null || zipCode.isBlank()) {
            throw new IllegalArgumentException("우편번호는 필수입니다.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("주소는 필수입니다.");
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }

    public Long getUserId()                          { return userId; }
    public void setUserId(Long userId)               { this.userId = userId; }

    public String getRecipientName()                 { return recipientName; }
    public void setRecipientName(String v)           { this.recipientName = v; }

    public String getPhone()                         { return phone; }
    public void setPhone(String phone)               { this.phone = phone; }

    public String getZipCode()                       { return zipCode; }
    public void setZipCode(String zipCode)           { this.zipCode = zipCode; }

    public String getAddress()                       { return address; }
    public void setAddress(String address)            { this.address = address; }

    public String getAddressDetail()                 { return addressDetail; }
    public void setAddressDetail(String v)           { this.addressDetail = v; }

    public boolean isDefault()                       { return isDefault; }
    public void setDefault(boolean isDefault)         { this.isDefault = isDefault; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime t)        { this.createdAt = t; }

    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t)        { this.updatedAt = t; }
}
