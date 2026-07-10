package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SellerSettlementMetaJdbcAdapterTest {

    @Mock JdbcTemplate jdbcTemplate;

    private SellerSettlementMetaJdbcAdapter adapter() {
        return new SellerSettlementMetaJdbcAdapter(jdbcTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("findByPaymentId: seller_id 가 있으면 채워진 메타를 반환")
    void findByPaymentId_withSeller_returnsMeta() throws SQLException {
        SellerSettlementMetaJdbcAdapter adapter = adapter();
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("seller_id")).thenReturn(42L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString("seller_tier")).thenReturn("VIP");
        when(rs.getString("settlement_cycle")).thenReturn("T_PLUS_3");

        SellerSettlementMeta expected = new SellerSettlementMeta(42L, "VIP", "T_PLUS_3");
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(1L)))
                .thenAnswer(inv -> {
                    RowMapper<SellerSettlementMeta> mapper = inv.getArgument(1);
                    return mapper.mapRow(rs, 0);
                });

        Optional<SellerSettlementMeta> result = adapter.findByPaymentId(1L);

        assertThat(result).contains(expected);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("findByPaymentId: seller_id 컬럼이 NULL 이면 sellerId=null (LEFT JOIN 미할당)")
    void findByPaymentId_nullSeller_returnsNullSellerId() throws SQLException {
        SellerSettlementMetaJdbcAdapter adapter = adapter();
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("seller_id")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getString("seller_tier")).thenReturn(null);
        when(rs.getString("settlement_cycle")).thenReturn(null);

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(2L)))
                .thenAnswer(inv -> {
                    RowMapper<SellerSettlementMeta> mapper = inv.getArgument(1);
                    return mapper.mapRow(rs, 0);
                });

        Optional<SellerSettlementMeta> result = adapter.findByPaymentId(2L);

        assertThat(result).isPresent();
        assertThat(result.get().sellerId()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("findByPaymentId: payment/order/product 체인이 없으면 empty")
    void findByPaymentId_missingChain_returnsEmpty() {
        SellerSettlementMetaJdbcAdapter adapter = adapter();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(999L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<SellerSettlementMeta> result = adapter.findByPaymentId(999L);

        assertThat(result).isEmpty();
    }
}
