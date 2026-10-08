-- 到店预约表（智能客服 Function Calling 写入的业务表）
CREATE TABLE IF NOT EXISTS `tb_appointment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL DEFAULT 0 COMMENT '预约用户id',
  `shop_id` bigint NOT NULL COMMENT '商家id',
  `shop_name` varchar(128) DEFAULT NULL COMMENT '商家名称（冗余）',
  `time_slot` varchar(64) NOT NULL COMMENT '到店时间',
  `people_count` int NOT NULL DEFAULT 1 COMMENT '到店人数',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1-已预约 2-已到店 3-已取消',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到店预约表';
