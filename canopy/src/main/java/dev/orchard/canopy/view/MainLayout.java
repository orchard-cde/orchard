package dev.orchard.canopy.view;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    public MainLayout() {
        createHeader();
        createDrawer();
    }

    private void createHeader() {
        H1 logo = new H1("Orchard Canopy");
        logo.addClassNames(
            LumoUtility.FontSize.LARGE,
            LumoUtility.Margin.MEDIUM
        );
        logo.getStyle()
            .set("color", "var(--lumo-primary-color)")
            .set("font-weight", "bold");

        Span tagline = new Span("See the Forest Through the Trees");
        tagline.addClassNames(
            LumoUtility.FontSize.SMALL,
            LumoUtility.TextColor.SECONDARY
        );

        VerticalLayout branding = new VerticalLayout(logo, tagline);
        branding.setSpacing(false);
        branding.setPadding(false);

        HorizontalLayout header = new HorizontalLayout(
            new DrawerToggle(),
            branding
        );
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(branding);
        header.setWidthFull();
        header.addClassNames(
            LumoUtility.Padding.Vertical.NONE,
            LumoUtility.Padding.Horizontal.MEDIUM
        );

        addToNavbar(header);
    }

    private void createDrawer() {
        SideNav nav = new SideNav();

        nav.addItem(new SideNavItem("Forest Overview",
            ForestView.class,
            VaadinIcon.GRID_BIG.create()));

        nav.addItem(new SideNavItem("My Groves",
            GrovesView.class,
            VaadinIcon.TREE_TABLE.create()));

        nav.addItem(new SideNavItem("Plant Grove",
            PlantGroveView.class,
            VaadinIcon.PLUS_CIRCLE.create()));

        nav.addItem(new SideNavItem("Nursery",
            NurseryView.class,
            VaadinIcon.COG.create()));

        VerticalLayout drawerContent = new VerticalLayout(nav);
        drawerContent.setSizeFull();
        drawerContent.setPadding(true);

        addToDrawer(drawerContent);
    }
}
