/**
 * orchestration-context —— 编排域：管线状态机、任务分片、断点续跑、逐链路确认门
 *
 * <p>接口适配层：REST 控制器、SPI 暴露、协议转换；不含业务规则，只调用 application 用例
 */
package online.yudream.voxelith.orchestration.interfaces;
