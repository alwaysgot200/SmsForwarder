package cn.ppps.forwarder;

import com.yanzhenjie.andserver.annotation.AppInfo;

/**
 * AndServer 的 AppInfo 标记类（静态替代 andserver-plugin 生成的同名产物）。
 *
 * 原 andserver-plugin（Transform 实现，AGP 8 下不可用）在构建期生成一个
 * 带 @AppInfo(applicationId) 的随机名类；andserver-processor 依赖该注解读取
 * applicationId，把注册类（AdapterRegister/ConfigRegister/...）生成到
 * {applicationId}.andserver.processor.generator 包，供运行时 ComponentRegister 反射加载。
 * 手写此类即可无损替代插件，注册类生成与运行时行为完全一致。
 */
@AppInfo("cn.ppps.forwarder")
public final class AndServerAppInfo {
}
