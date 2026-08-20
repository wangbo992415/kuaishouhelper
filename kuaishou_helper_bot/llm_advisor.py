"""
LLM 增强模块 - 模糊场景判断
当规则引擎无法明确判断时，调用云端 LLM 给出操作建议

部署方式：部署为一个简单的 HTTP 服务（FastAPI），手机端通过 HTTPS 调用
或者直接集成腾讯云 SCF（Serverless），按调用量计费，成本极低

安全设计：
- 只发送「页面状态描述文本」，不发送截图原图（避免隐私问题）
- 提示词明确限制输出格式（JSON），防止注入
- 设置超时和降级（LLM 不可用时回退到规则引擎）
"""

import json
import asyncio
from typing import Optional
from openai import AsyncOpenAI  # 可替换为腾讯混元 SDK

# ============================================================
# 配置
# ============================================================
SYSTEM_PROMPT = """你是一个手机应用使用助手，专门帮助用户更高效地使用快手极速版领金币。
你的任务：根据用户当前屏幕的状态描述，给出最合理的下一步操作建议。

规则：
1. 输出必须是 JSON 格式：{"action": "建议的操作", "reason": "简短理由", "priority": "high/normal/low"}
2. action 必须是以下之一：滑动下一条、点击领取、等待广告结束、打开宝箱、返回首页、休息一下、无需操作
3. 理由不超过 15 个字
4. 如果不确定，返回 {"action": "无需操作", "reason": "状态不明确", "priority": "low"}
5. 绝对不要建议任何违规操作（如刷量、多开、自动脚本等）
"""

# ============================================================
# 核心函数
# ============================================================

async def get_advice(page_state: dict) -> Optional[dict]:
    """
    调用 LLM 获取操作建议
    
    Args:
        page_state: 页面状态字典（来自 Android 端的状态感知层）
    
    Returns:
        {"action": str, "reason": str, "priority": str} 或 None（降级）
    """
    # 构造用户消息
    user_message = f"当前页面状态：\n{json.dumps(page_state, ensure_ascii=False, indent=2)}"
    
    try:
        client = AsyncOpenAI()  # 初始化你的 LLM 客户端
        response = await client.chat.completions.create(
            model="hunyuan-pro",  # 或 gpt-4o-mini 等低成本模型
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_message}
            ],
            temperature=0.3,  # 低温度，保证输出稳定
            max_tokens=150,
            response_format={"type": "json_object"}  # 强制 JSON 输出
        )
        
        result_text = response.choices[0].message.content
        result = json.loads(result_text)
        
        # 验证格式
        assert "action" in result
        assert result["action"] in [
            "滑动下一条", "点击领取", "等待广告结束", 
            "打开宝箱", "返回首页", "休息一下", "无需操作"
        ]
        
        return result
        
    except Exception as e:
        print(f"⚠️ LLM 调用失败，降级到规则引擎: {e}")
        return None  # 降级


# ============================================================
# HTTP 服务（FastAPI）
# ============================================================
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="快手助手 LLM 顾问", version="1.0")

class AdviceRequest(BaseModel):
    page_state: dict

class AdviceResponse(BaseModel):
    action: str
    reason: str
    priority: str
    source: str  # "llm" or "fallback"

@app.post("/advise", response_model=AdviceResponse)
async def advise(req: AdviceRequest):
    """获取操作建议"""
    result = await get_advice(req.page_state)
    
    if result:
        return AdviceResponse(**result, source="llm")
    else:
        # 降级：返回通用建议
        return AdviceResponse(
            action="无需操作",
            reason="网络异常，稍后再试",
            priority="low",
            source="fallback"
        )

@app.get("/health")
async def health():
    return {"status": "ok"}

# ============================================================
# 本地测试
# ============================================================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.1", port=8000)
