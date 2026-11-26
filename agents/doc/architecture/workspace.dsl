workspace "Shop" "An online shop, from the big picture down to its components" {

    model {
        customer = person "Customer" "Buys products in the shop"
        support = person "Support" "Helps customers" "Staff"

        shop = softwareSystem "Shop" "Sells products online" {
            web = container "Web App" "Lets customers browse and order" "React"
            api = container "Order API" "Takes and tracks orders" "Spring Boot" {
                orders = component "Order Controller" "Accepts orders" "Spring MVC"
                billing = component "Billing" "Charges orders" "Spring Bean"
            }
            db = container "Database" "Stores orders" "PostgreSQL" "Database"
        }
        payment = softwareSystem "Payment Provider" "Charges credit cards" "External"

        customer -> web "Orders products" "HTTPS"
        support -> api "Looks up orders" "HTTPS"
        web -> orders "Sends orders" "JSON/HTTPS"
        orders -> billing "Bills the order"
        billing -> payment "Charges" "HTTPS"
        api -> db "Reads and writes" "JDBC"

        live = deploymentEnvironment "Live" {
            deploymentNode "Cloud" "" "AWS" {
                deploymentNode "App Server" "" "Ubuntu" {
                    containerInstance api
                }
                deploymentNode "Database Server" "" "Amazon RDS" {
                    containerInstance db
                }
            }
        }
    }

    views {
        systemLandscape landscape {
            include *
        }
        systemContext shop context {
            include *
        }
        container shop containers {
            include *
        }
        component api components {
            include *
        }
        deployment shop "Live" deployment {
            include *
        }
        dynamic shop ordering "Placing an order" {
            customer -> web "Places an order"
            web -> api "Sends the order"
            api -> payment "Charges the card"
        }
    }
}
