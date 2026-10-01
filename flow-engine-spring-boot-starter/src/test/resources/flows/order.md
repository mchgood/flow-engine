# orderFlow

```mermaid
flowchart TD
    start([开始]) --> check["检查库存"]
    check --> fulfillment[["调用履约"]]
    fulfillment --> finish([结束])
```
