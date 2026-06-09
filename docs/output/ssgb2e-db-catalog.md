# ssgb2e 전체 테이블 카탈로그 & Read/Write 매트릭스
> 분석 대상: `ssgb2e-api_20250721`, `ssgb2e-backoffice-final`, `ssgb2e-quartz-final` (MyBatis 매퍼 SQL 정적 파싱)
> 표기: `R`=조회(from/join), `W`=변경(insert/update/delete), `WR`=둘 다, `-`=미접근

## 요약
- **총 물리 테이블: 211개** (`tbl_*`)
- 앱별 접근: API 68 · Backoffice 202 · Quartz 50
- **3개 앱 공유 테이블: 19개** (강결합 핵심 — 변경 시 3개 앱 동시 영향)
- **2개+ 앱이 동시 WRITE: 67개** (쓰기 정합성/락 충돌 위험 구간)
- 전용 테이블: API 1 · Quartz 7 · Backoffice 113 (Backoffice가 사실상 마스터)

## 1. 3개 앱 공유 핵심 테이블 (19)
| 테이블 | API | BO | QZ | 도메인 |
|---|---|---|---|---|
| `tbl_couponbank` | W | WR | WR | 쿠폰함 |
| `tbl_delivery_corp` | R | WR | R | 택배사 |
| `tbl_member` | R | WR | WR | 회원 |
| `tbl_mng_member` | R | WR | WR | 관리자 |
| `tbl_offline_order_history` | WR | WR | W | 오프라인주문 이력 |
| `tbl_offlinemall_orderinfo` | WR | WR | WR | 오프라인몰 주문 |
| `tbl_order_amount` | WR | WR | R | 주문 금액 |
| `tbl_order_gms` | WR | WR | WR | GMS 파트너주문 |
| `tbl_order_gms_log` | W | WR | W | GMS 로그 |
| `tbl_order_gms_log_dt` | W | WR | W | GMS 로그상세 |
| `tbl_order_history` | W | WR | W | 주문 이력 |
| `tbl_order_optlist` | R | R | R | 주문 옵션 |
| `tbl_orderinfo` | WR | WR | WR | 주문 헤더 |
| `tbl_orderplist` | WR | WR | WR | 주문 상품라인 |
| `tbl_product` | WR | WR | R | 상품 |
| `tbl_promotion_main` | R | WR | R | 프로모션 |
| `tbl_ptncfginfo` | WR | WR | R | 파트너 설정 |
| `tbl_ptnmember` | WR | WR | WR | 파트너 |
| `tbl_sellmember` | R | WR | WR | 셀러 |

## 2. 다중 쓰기 충돌 위험 테이블 (2개+ 앱 WRITE, 67)
| 테이블 | API | BO | QZ |
|---|---|---|---|
| `tbl_bi_cpncounsel` | - | WR | WR |
| `tbl_cart` | WR | WR | - |
| `tbl_cart_optlist` | WR | WR | - |
| `tbl_couponbank` | W | WR | WR |
| `tbl_couponbank_delete_log` | - | W | W |
| `tbl_log_ptn` | WR | WR | - |
| `tbl_log_pwd_chg` | WR | WR | - |
| `tbl_long_member` | - | WR | WR |
| `tbl_mem_memo` | WR | WR | - |
| `tbl_member` | R | WR | WR |
| `tbl_mileagebank` | - | WR | WR |
| `tbl_mileagebank_use_log` | - | WR | W |
| `tbl_mileagebankv` | - | WR | W |
| `tbl_mng_member` | R | WR | WR |
| `tbl_mngmemb_history` | - | WR | WR |
| `tbl_offline_order_history` | WR | WR | W |
| `tbl_offline_orderinvoice_history` | WR | WR | - |
| `tbl_offlinemall` | WR | WR | - |
| `tbl_offlinemall_addinvoice` | WR | WR | - |
| `tbl_offlinemall_orderinfo` | WR | WR | WR |
| `tbl_offlinemall_orderinfo_tmp` | WR | WR | - |
| `tbl_offlinemall_orderinfo_tmp_log` | WR | WR | - |
| `tbl_offlinemall_orderinforeset` | WR | WR | - |
| `tbl_order_advice_memo` | WR | WR | - |
| `tbl_order_amount` | WR | WR | R |
| `tbl_order_cash_receipt_req` | - | WR | WR |
| `tbl_order_cash_receipt_req_body` | - | W | W |
| `tbl_order_gms` | WR | WR | WR |
| `tbl_order_gms_log` | W | WR | W |
| `tbl_order_gms_log_dt` | W | WR | W |
| `tbl_order_history` | W | WR | W |
| `tbl_order_optlist_sepa_stg` | W | W | - |
| `tbl_orderinfo` | WR | WR | WR |
| `tbl_orderinfo_sepa_stg` | W | W | - |
| `tbl_orderinforeset` | WR | WR | - |
| `tbl_orderinvoice_history` | WR | WR | - |
| `tbl_orderplist` | WR | WR | WR |
| `tbl_orderplist_sepa_stg` | W | W | - |
| `tbl_pointbank` | - | WR | WR |
| `tbl_pointbank_use_log` | - | WR | W |
| `tbl_pointbankv` | - | WR | W |
| `tbl_prd_cty_code` | WR | WR | - |
| `tbl_product` | WR | WR | R |
| `tbl_product_history` | WR | WR | - |
| `tbl_product_noti` | WR | WR | - |
| `tbl_product_opt_define` | WR | WR | - |
| `tbl_product_opt_group_define` | WR | WR | - |
| `tbl_product_request` | WR | WR | - |
| `tbl_product_stock` | WR | WR | - |
| `tbl_product_tmp` | WR | WR | - |
| `tbl_product_todayview` | WR | WR | - |
| `tbl_productoption` | WR | WR | - |
| `tbl_proposal` | WR | WR | - |
| `tbl_proposal_prd` | WR | WR | - |
| `tbl_ptn_copyproduct` | WR | WR | - |
| `tbl_ptn_counsel` | - | WR | WR |
| `tbl_ptn_sales_member` | WR | WR | - |
| `tbl_ptn_sub_manager` | WR | WR | - |
| `tbl_ptncfginfo` | WR | WR | R |
| `tbl_ptnmemb_history` | - | WR | WR |
| `tbl_ptnmember` | WR | WR | WR |
| `tbl_ptnmember_reset` | WR | WR | - |
| `tbl_safety_num` | - | WR | WR |
| `tbl_sellmemb_history` | - | WR | WR |
| `tbl_sellmember` | R | WR | WR |
| `tbl_wish` | WR | WR | - |
| `tbl_wish_optlist` | WR | WR | - |

## 3. 전체 카탈로그 (211)
| 테이블 | API | BO | QZ |
|---|---|---|---|
| `tbl_admin_auth` | - | WR | - |
| `tbl_affiliate` | R | WR | - |
| `tbl_attendance` | - | WR | - |
| `tbl_attendance_apply` | - | R | - |
| `tbl_attendance_success` | - | R | - |
| `tbl_backoffice_menu` | - | WR | - |
| `tbl_batch_error_detail_log` | - | - | W |
| `tbl_batch_log` | - | - | W |
| `tbl_bi_cpncounsel` | - | WR | WR |
| `tbl_bi_notice` | - | WR | - |
| `tbl_cancel_order_detail` | - | W | - |
| `tbl_cart` | WR | WR | - |
| `tbl_cart_optlist` | WR | WR | - |
| `tbl_cart_optlist_coupon` | - | WR | - |
| `tbl_cmboard` | - | WR | - |
| `tbl_cmm_board` | - | WR | - |
| `tbl_common_code` | R | WR | - |
| `tbl_coupon_isn` | - | WR | R |
| `tbl_coupon_product` | - | W | - |
| `tbl_couponbank` | W | WR | WR |
| `tbl_couponbank_delete_log` | - | W | W |
| `tbl_delivery_corp` | R | WR | R |
| `tbl_display_main` | - | WR | - |
| `tbl_display_mainbrd` | - | WR | - |
| `tbl_display_maincty` | - | WR | - |
| `tbl_display_mainctyicon` | - | WR | - |
| `tbl_display_mainexb` | - | WR | - |
| `tbl_display_mainprd` | R | WR | - |
| `tbl_doc_item_code` | - | WR | - |
| `tbl_doc_item_code_detail` | - | WR | - |
| `tbl_easypay8` | - | W | - |
| `tbl_easypay_noti` | W | - | R |
| `tbl_faq` | - | WR | - |
| `tbl_file` | - | WR | - |
| `tbl_file_detail` | - | WR | - |
| `tbl_filedown_dt_log` | - | W | - |
| `tbl_filedown_log` | - | WR | - |
| `tbl_giftmallorder` | - | R | - |
| `tbl_grade_auth` | - | WR | - |
| `tbl_identity_verified_log` | - | WR | - |
| `tbl_inbbs_notice` | - | WR | - |
| `tbl_kkfimage_list` | - | R | - |
| `tbl_kko_templist` | - | R | - |
| `tbl_leave_member` | - | R | - |
| `tbl_log_admin` | - | WR | - |
| `tbl_log_ptn` | WR | WR | - |
| `tbl_log_pwd_chg` | WR | WR | - |
| `tbl_log_sabangnet` | W | - | - |
| `tbl_log_sell` | - | W | - |
| `tbl_login_log` | - | WR | - |
| `tbl_long_member` | - | WR | WR |
| `tbl_luckybox` | - | WR | - |
| `tbl_luckybox_item` | - | R | - |
| `tbl_luckybox_price` | - | R | - |
| `tbl_main_advert` | - | WR | - |
| `tbl_main_display` | - | WR | - |
| `tbl_mem_memo` | WR | WR | - |
| `tbl_member` | R | WR | WR |
| `tbl_member_delivery_place` | - | WR | - |
| `tbl_member_history` | - | R | - |
| `tbl_member_upload_chk` | - | WR | - |
| `tbl_member_upload_chk_col` | - | WR | - |
| `tbl_member_upload_log` | - | WR | - |
| `tbl_member_upload_log_detail` | - | WR | - |
| `tbl_memberreset` | - | WR | - |
| `tbl_memdelete_remove` | - | WR | - |
| `tbl_memsms` | - | R | - |
| `tbl_memupdate_failhistory` | - | WR | - |
| `tbl_memupdate_history` | - | WR | - |
| `tbl_mileage_history` | - | W | - |
| `tbl_mileage_ptnlist` | - | WR | - |
| `tbl_mileage_setting` | - | WR | - |
| `tbl_mileage_upload_chk` | - | WR | - |
| `tbl_mileage_upload_chk_col` | - | WR | - |
| `tbl_mileagebank` | - | WR | WR |
| `tbl_mileagebank_use_log` | - | WR | W |
| `tbl_mileagebankv` | - | WR | W |
| `tbl_mng_member` | R | WR | WR |
| `tbl_mng_open_win` | - | WR | - |
| `tbl_mngmemb_history` | - | WR | WR |
| `tbl_mngmemb_viewhistory` | - | WR | - |
| `tbl_normemb_history` | - | WR | - |
| `tbl_noti_code` | R | WR | - |
| `tbl_noti_target` | - | WR | - |
| `tbl_notice` | - | WR | - |
| `tbl_offline_order_history` | WR | WR | W |
| `tbl_offline_orderinvoice_history` | WR | WR | - |
| `tbl_offlinemall` | WR | WR | - |
| `tbl_offlinemall_addinvoice` | WR | WR | - |
| `tbl_offlinemall_orderinfo` | WR | WR | WR |
| `tbl_offlinemall_orderinfo_tmp` | WR | WR | - |
| `tbl_offlinemall_orderinfo_tmp_log` | WR | WR | - |
| `tbl_offlinemall_orderinforeset` | WR | WR | - |
| `tbl_open_win` | - | WR | - |
| `tbl_open_win_ptnlist` | - | WR | - |
| `tbl_order_advice_memo` | WR | WR | - |
| `tbl_order_amount` | WR | WR | R |
| `tbl_order_cash_receipt_req` | - | WR | WR |
| `tbl_order_cash_receipt_req_body` | - | W | W |
| `tbl_order_cash_receipt_res_body` | - | WR | R |
| `tbl_order_cash_receipt_tran_history` | - | WR | - |
| `tbl_order_detail_statistics` | - | R | W |
| `tbl_order_gms` | WR | WR | WR |
| `tbl_order_gms_cfm` | - | WR | - |
| `tbl_order_gms_cfm_dt` | - | WR | - |
| `tbl_order_gms_dev` | - | - | WR |
| `tbl_order_gms_log` | W | WR | W |
| `tbl_order_gms_log_dev` | - | - | W |
| `tbl_order_gms_log_dt` | W | WR | W |
| `tbl_order_gms_log_dt_dev` | - | - | W |
| `tbl_order_history` | W | WR | W |
| `tbl_order_multi` | - | R | - |
| `tbl_order_noti` | - | R | - |
| `tbl_order_optlist` | R | R | R |
| `tbl_order_optlist_sepa_stg` | W | W | - |
| `tbl_order_receipt_req` | - | R | - |
| `tbl_order_receipt_tran_history` | - | R | - |
| `tbl_order_refund_history` | - | WR | - |
| `tbl_order_statistics` | - | R | W |
| `tbl_orderinfo` | WR | WR | WR |
| `tbl_orderinfo_dev` | - | - | WR |
| `tbl_orderinfo_sepa_stg` | W | W | - |
| `tbl_orderinfo_tmp` | R | R | - |
| `tbl_orderinforeset` | WR | WR | - |
| `tbl_orderinvoice_etc` | R | WR | - |
| `tbl_orderinvoice_history` | WR | WR | - |
| `tbl_orderplist` | WR | WR | WR |
| `tbl_orderplist_dev` | - | - | WR |
| `tbl_orderplist_sepa_stg` | W | W | - |
| `tbl_partner_mall` | R | WR | - |
| `tbl_point_history` | - | WR | - |
| `tbl_point_upload_chk` | - | WR | - |
| `tbl_point_upload_chk_col` | - | WR | - |
| `tbl_pointbank` | - | WR | WR |
| `tbl_pointbank_gift` | - | WR | - |
| `tbl_pointbank_use_log` | - | WR | W |
| `tbl_pointbankv` | - | WR | W |
| `tbl_prd_cty_code` | WR | WR | - |
| `tbl_product` | WR | WR | R |
| `tbl_product_dellist` | - | WR | - |
| `tbl_product_display` | - | WR | - |
| `tbl_product_etc` | R | R | - |
| `tbl_product_history` | WR | WR | - |
| `tbl_product_noti` | WR | WR | - |
| `tbl_product_one` | R | WR | - |
| `tbl_product_opt_define` | WR | WR | - |
| `tbl_product_opt_group_define` | WR | WR | - |
| `tbl_product_qna` | R | WR | - |
| `tbl_product_request` | WR | WR | - |
| `tbl_product_sample` | - | WR | - |
| `tbl_product_stock` | WR | WR | - |
| `tbl_product_tmp` | WR | WR | - |
| `tbl_product_todayview` | WR | WR | - |
| `tbl_productoption` | WR | WR | - |
| `tbl_promotion_cty_code` | - | WR | - |
| `tbl_promotion_main` | R | WR | R |
| `tbl_promotion_prd` | - | WR | R |
| `tbl_proposal` | WR | WR | - |
| `tbl_proposal_prd` | WR | WR | - |
| `tbl_ptn_cate` | R | WR | - |
| `tbl_ptn_cate_point` | - | WR | - |
| `tbl_ptn_copyproduct` | WR | WR | - |
| `tbl_ptn_counsel` | - | WR | WR |
| `tbl_ptn_limit_cate` | - | WR | - |
| `tbl_ptn_limit_prd` | - | R | - |
| `tbl_ptn_notice` | - | WR | - |
| `tbl_ptn_open_win` | - | WR | - |
| `tbl_ptn_sales_member` | WR | WR | - |
| `tbl_ptn_sub_manager` | WR | WR | - |
| `tbl_ptncfginfo` | WR | WR | R |
| `tbl_ptnmemb_history` | - | WR | WR |
| `tbl_ptnmember` | WR | WR | WR |
| `tbl_ptnmember_notice` | - | WR | - |
| `tbl_ptnmember_reset` | WR | WR | - |
| `tbl_safety_num` | - | WR | WR |
| `tbl_safety_num_setting` | - | WR | - |
| `tbl_security_chk_code` | - | R | - |
| `tbl_security_chk_log` | - | WR | R |
| `tbl_security_chk_log_detail` | - | WR | - |
| `tbl_security_dest_log` | - | WR | - |
| `tbl_security_oath_log` | - | WR | - |
| `tbl_securitychk` | - | WR | - |
| `tbl_sell_notice` | - | WR | - |
| `tbl_sell_notice_file` | - | WR | - |
| `tbl_sell_notice_hisotry` | - | WR | - |
| `tbl_sell_open_win` | - | WR | - |
| `tbl_sell_site_agreement` | - | WR | - |
| `tbl_sell_site_security` | - | WR | - |
| `tbl_sell_smsnotice` | - | WR | - |
| `tbl_sell_smsnotice_tmp` | - | WR | - |
| `tbl_sell_sub_manager` | R | WR | - |
| `tbl_sellcontract` | - | WR | R |
| `tbl_sellmemb_history` | - | WR | WR |
| `tbl_sellmember` | R | WR | WR |
| `tbl_sellmember_popup` | - | WR | - |
| `tbl_sendinfo` | - | WR | - |
| `tbl_sendlist` | - | WR | - |
| `tbl_site_agreement` | - | WR | - |
| `tbl_site_security` | - | WR | - |
| `tbl_site_security2` | - | WR | - |
| `tbl_spointbank` | - | WR | - |
| `tbl_spointbank_use_log` | - | WR | - |
| `tbl_spointbankv` | - | WR | - |
| `tbl_status_history` | - | WR | - |
| `tbl_sub_manager_history` | - | WR | - |
| `tbl_systemrequest` | - | WR | - |
| `tbl_vdi_info` | - | R | - |
| `tbl_verification_code` | - | WR | - |
| `tbl_wish` | WR | WR | - |
| `tbl_wish_optlist` | WR | WR | - |
| `tbl_workrequest` | - | WR | - |
