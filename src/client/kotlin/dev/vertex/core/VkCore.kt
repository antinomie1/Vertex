package dev.vertex.core

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkAllocationCallbacks
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties

/**
 * G0: 进程内自有 Vulkan 栈的最小引导（无 WSI——尚无 surface 需求）。
 * 独立于 Blaze3D；G1 的协同驻留单设备改造（DESIGN.md §1）在此对象上演进。
 */
object VkCore {
    lateinit var instance: VkInstance; private set
    lateinit var device: VkDevice; private set
    lateinit var graphicsQueue: VkQueue; private set
    var graphicsFamily: Int = -1; private set
    var gpuName: String = ""; private set

    fun bootstrap() {
        stackPush().use { s ->
            val appInfo = VkApplicationInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(s.UTF8("vertex"))
                .apiVersion(VK_MAKE_VERSION(1, 3, 0))

            val ci = VkInstanceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)

            val pInstance = s.mallocPointer(1)
            vkCheck(vkCreateInstance(ci, null, pInstance), "vkCreateInstance")
            instance = VkInstance(pInstance[0], ci)

            // 物理设备：优先独立显卡
            val pCount = s.mallocInt(1)
            vkCheck(vkEnumeratePhysicalDevices(instance, pCount, null), "enumerate")
            val buf = s.mallocPointer(pCount[0])
            vkCheck(vkEnumeratePhysicalDevices(instance, pCount, buf), "enumerate")

            var picked: VkPhysicalDevice? = null
            var pickedDiscrete = false
            val props = VkPhysicalDeviceProperties.calloc(s)
            for (i in 0 until pCount[0]) {
                val pd = VkPhysicalDevice(buf.get(i), instance)
                vkGetPhysicalDeviceProperties(pd, props)
                val discrete = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
                if (picked == null || (discrete && !pickedDiscrete)) {
                    picked = pd; pickedDiscrete = discrete; gpuName = props.deviceNameString()
                }
            }
            val physical = picked ?: throw IllegalStateException("no Vulkan physical device")
            graphicsFamily = findGraphicsFamily(physical)

            // 设备：单图形队列；特性全默认（G0 无需求）
            val priority = s.mallocFloat(1).put(0, 1.0f)
            val queueCi = VkDeviceQueueCreateInfo.calloc(1, s)
            queueCi[0].sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsFamily)
                .pQueuePriorities(priority)
            val features = VkPhysicalDeviceFeatures.calloc(s)
            val devCi = VkDeviceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCi)
                .pEnabledFeatures(features)

            val pDevice = s.mallocPointer(1)
            vkCheck(org.lwjgl.vulkan.VK10.vkCreateDevice(physical, devCi, null as VkAllocationCallbacks?, pDevice), "vkCreateDevice")

            device = VkDevice(pDevice[0], physical, devCi)

            val pQueue = s.mallocPointer(1)
            vkGetDeviceQueue(device, graphicsFamily, 0, pQueue)
            graphicsQueue = VkQueue(pQueue[0], device)
        }
    }

    private fun findGraphicsFamily(pd: VkPhysicalDevice): Int {
        stackPush().use { s ->
            val pCount = s.mallocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, null)
            val families = VkQueueFamilyProperties.calloc(pCount[0], s)
            vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, families)
            for (i in 0 until pCount[0]) {
                if (families[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) return i
            }
        }
        throw IllegalStateException("no graphics queue family")
    }

    fun shutdown() {
        if (!::device.isInitialized) return
        vkDestroyDevice(device, null)
        vkDestroyInstance(instance, null)
    }
}

/** VkResult → 带调用名的异常；全部调用点走它。 */
internal fun vkCheck(result: Int, what: String) {
    if (result != VK_SUCCESS) throw IllegalStateException("$what failed: VkResult $result")
}
