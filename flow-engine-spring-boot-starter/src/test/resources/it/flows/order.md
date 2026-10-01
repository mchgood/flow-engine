# orderFlow

```mermaid
flowchart TD
    start([开始]) --> check["检查库存"]
    check --> childFlow[["调用子流程"]]
    childFlow --> finish([结束])
```
