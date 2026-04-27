package github.lms.lemuel.user.application.port.out;

/**
 * JWT 토큰 제공 Outbound Port
 */
public interface TokenProviderPort {

    String generateToken(String email, String role);

    boolean validateToken(String token);

    String getEmailFromToken(String token);
}
